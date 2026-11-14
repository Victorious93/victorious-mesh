// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// DialFunc opens a protected (VpnService.protect) socket to the proxy.
type DialFunc func(ctx context.Context, network, address string) (net.Conn, error)

var ErrUDPNotSupported = errors.New("upstream does not support SOCKS5 UDP ASSOCIATE")

type Dialer struct {
	Cfg    Config
	Dial   DialFunc // used to reach the proxy itself
	Direct DialFunc // used only when FailClosed is false and the proxy is down
	// Quiet keeps this dialer from reading or writing the forced-mode
	// DNS/IPv6 status. Per-app proxies set it so one app's proxy cannot
	// change the device-wide forced-proxy state.
	Quiet bool
	// TLS, if set, is the base TLS config for https proxies (tests set
	// RootCAs here). ServerName is always forced from Cfg and
	// InsecureSkipVerify is always cleared: certificate failures can
	// never be configured away or retried in plaintext.
	TLS *tls.Config
	// Names resolves reserved-name virtual addresses (Tor/I2P) back to
	// their names. nil means no reserved-name handling at this layer.
	Names NameResolver
	// SAM is the shared I2P session manager for KindSAM / BackendI2PSAM.
	SAM *SAMManager
}

func (d Dialer) noteIPv6(state string) {
	if !d.Quiet {
		noteIPv6(state)
	}
}

func (d Dialer) noteDNS(state string) {
	if !d.Quiet {
		noteDNS(state)
	}
}

func (d Dialer) ipv6Blocked() bool {
	return !d.Quiet && IPv6State() == IPv6Blocked
}

func (d Dialer) dialProxy(ctx context.Context) DialFunc {
	dial := d.Dial
	if dial == nil {
		dial = defaultDial
	}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		c, err := dial(ctx, network, address)
		if err != nil && !d.Quiet {
			noteProxyDialFailed()
		}
		return c, err
	}
}

func defaultDial(ctx context.Context, network, address string) (net.Conn, error) {
	var nd net.Dialer
	return nd.DialContext(ctx, network, address)
}

// DialTCP opens dest (host:port) through the upstream. No direct fallback.
func (d Dialer) DialTCP(ctx context.Context, dest string) (net.Conn, error) {
	if !d.Cfg.Enabled() {
		return nil, errors.New("forced upstream is off")
	}
	dest, err0 := d.gateDest(dest)
	if err0 != nil {
		return nil, err0
	}
	if destIsIPv6(dest) && d.ipv6Blocked() {
		return nil, ErrIPv6Blocked
	}
	// Public DNS is always fail-closed: never the underlay, even if
	// the user left FailClosed off. Mesh DNS never reaches DialTCP.
	if p, ok := destPort(dest); ok && p == dnsPort {
		d.Cfg.FailClosed = true
		d.Direct = nil
	}
	var (
		c   net.Conn
		err error
	)
	switch d.Cfg.Kind {
	case KindHTTP, KindHTTPS:
		c, err = d.dialHTTPConnect(ctx, dest)
	case KindSOCKS5:
		c, err = d.dialSOCKS5TCP(ctx, dest)
	case KindSAM:
		c, err = d.dialSAM(ctx, dest)
	default:
		return nil, fmt.Errorf("unsupported kind %q", d.Cfg.Kind)
	}
	if err == nil {
		if destIsIPv6(dest) {
			d.noteIPv6(IPv6ViaProxy)
		}
		if p, ok := destPort(dest); ok && p == dnsPort {
			d.noteDNS("tcp")
		}
		return c, nil
	}
	if p, ok := destPort(dest); ok && p == dnsPort {
		d.noteDNS("blocked")
	}
	if destIsIPv6(dest) {
		if ipv6RejectedByProxy(err) {
			d.noteIPv6(IPv6Blocked)
		}
		return nil, err
	}
	if d.Cfg.FailClosed || d.Direct == nil || d.Cfg.Backend != BackendNone || ClassifyName(hostOf(dest)).Reserved() {
		// Tor/I2P gateways and reserved names never fall back to a
		// direct connection, whatever FailClosed says.
		return nil, err
	}
	return d.Direct(ctx, "tcp", dest)
}

// connectProxy reaches the proxy and, for https proxies, completes a
// verified TLS handshake before any CONNECT or credential is written.
// A failed handshake is final: there is no plaintext retry.
func (d Dialer) connectProxy(ctx context.Context) (net.Conn, error) {
	c, err := d.dialProxy(ctx)(ctx, "tcp", d.Cfg.ProxyAddr())
	if err != nil {
		return nil, &StageError{Stage: StageReach, Err: err}
	}
	if d.Cfg.Kind != KindHTTPS {
		return c, nil
	}
	var base *tls.Config
	if d.TLS != nil {
		base = d.TLS.Clone()
	} else {
		base = &tls.Config{}
	}
	base.ServerName = d.Cfg.TLSName()
	base.InsecureSkipVerify = false
	base.VerifyPeerCertificate = nil
	base.VerifyConnection = nil
	if base.MinVersion < tls.VersionTLS12 {
		base.MinVersion = tls.VersionTLS12
	}
	base.NextProtos = []string{"http/1.1"} // CONNECT needs HTTP/1.1, not h2
	tc := tls.Client(c, base)
	if err := tc.HandshakeContext(ctx); err != nil {
		c.Close()
		return nil, &StageError{Stage: StageTLS, Err: err, Cert: isCertError(err)}
	}
	return tc, nil
}

func isCertError(err error) bool {
	var ua x509.UnknownAuthorityError
	var ci x509.CertificateInvalidError
	var hn x509.HostnameError
	var cv *tls.CertificateVerificationError
	return errors.As(err, &ua) || errors.As(err, &ci) || errors.As(err, &hn) || errors.As(err, &cv)
}

// watchCtx closes c if ctx is cancelled before the returned stop is
// called, so a cancelled dial never leaves a handshake blocked.
func watchCtx(ctx context.Context, c net.Conn) (stop func()) {
	done := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			c.Close()
		case <-done:
		}
	}()
	return func() { close(done) }
}

const maxConnectResponse = 16 << 10

func (d Dialer) dialHTTPConnect(ctx context.Context, dest string) (net.Conn, error) {
	c, err := d.connectProxy(ctx)
	if err != nil {
		return nil, err
	}
	pc, err := httpConnectOver(ctx, c, dest, d.Cfg.Username, d.Cfg.Password)
	if err != nil {
		c.Close()
		return nil, err
	}
	return pc, nil
}

// httpConnectOver performs one HTTP CONNECT on an already-open
// connection to a proxy (plain, TLS, or an I2P stream).
func httpConnectOver(ctx context.Context, c net.Conn, dest, user, pass string) (net.Conn, error) {
	stop := watchCtx(ctx, c)
	defer stop()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	} else {
		_ = c.SetDeadline(time.Now().Add(20 * time.Second))
	}
	req := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: dest},
		Host:   dest,
		Header: make(http.Header),
	}
	if user != "" {
		token := base64.StdEncoding.EncodeToString([]byte(user + ":" + pass))
		req.Header.Set("Proxy-Authorization", "Basic "+token)
	}
	if err := req.Write(c); err != nil {
		return nil, &StageError{Stage: StageNegotiate, Err: err}
	}
	br := bufio.NewReader(io.LimitReader(c, maxConnectResponse))
	resp, err := http.ReadResponse(br, req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &StageError{Stage: StageNegotiate, Err: fmt.Errorf("not an HTTP proxy reply: %w", err)}
	}
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusProxyAuthRequired, http.StatusUnauthorized:
		return nil, &StageError{Stage: StageAuth, Err: fmt.Errorf("CONNECT %s: %s", dest, resp.Status)}
	case http.StatusForbidden:
		st := StageForward
		if user == "" {
			st = StageAuth // a proxy that wants credentials often says 403
		}
		return nil, &StageError{Stage: st, Err: fmt.Errorf("CONNECT %s: %s", dest, resp.Status)}
	case http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return nil, &StageError{Stage: StageForward, Err: fmt.Errorf("CONNECT %s: %s", dest, resp.Status)}
	default:
		return nil, &StageError{Stage: StageNegotiate, Err: fmt.Errorf("CONNECT %s: %s", dest, resp.Status)}
	}
	_ = c.SetDeadline(time.Time{})
	if n := br.Buffered(); n > 0 {
		pre := make([]byte, n)
		_, _ = io.ReadFull(br, pre)
		return &prefixConn{Conn: c, r: bufio.NewReader(io.MultiReader(bytes.NewReader(pre), c))}, nil
	}
	return c, nil
}

type prefixConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) { return c.r.Read(p) }

func (d Dialer) dialSOCKS5TCP(ctx context.Context, dest string) (net.Conn, error) {
	host, portStr, err := net.SplitHostPort(dest)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("bad destination port %q", portStr)
	}
	c, err := d.dialProxy(ctx)(ctx, "tcp", d.Cfg.ProxyAddr())
	if err != nil {
		return nil, &StageError{Stage: StageReach, Err: err}
	}
	stop := watchCtx(ctx, c)
	defer stop()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	} else {
		_ = c.SetDeadline(time.Now().Add(20 * time.Second))
	}
	if err := socks5Handshake(c, d.Cfg.Username, d.Cfg.Password); err != nil {
		c.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	if err := socks5Request(c, 0x01, host, uint16(port)); err != nil {
		c.Close()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}

// socks5Request sends one SOCKS5 command (CONNECT 0x01, Tor RESOLVE
// 0xF0) for host:port and consumes the reply. A host that is not an IP
// literal is sent as a domain name (ATYP 3), unresolved, so the proxy
// (for example Tor) does the lookup.
func socks5Request(c net.Conn, cmd byte, host string, port uint16) error {
	req, err := socks5Encode(cmd, host, port)
	if err != nil {
		return &StageError{Stage: StageNegotiate, Err: err}
	}
	if _, err := c.Write(req); err != nil {
		return &StageError{Stage: StageNegotiate, Err: err}
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return &StageError{Stage: StageNegotiate, Err: err}
	}
	if hdr[0] != 5 {
		return &StageError{Stage: StageNegotiate, Err: fmt.Errorf("socks5: bad version %d in reply", hdr[0])}
	}
	if _, err := readSocksAddrRaw(c, hdr[3]); err != nil {
		return &StageError{Stage: StageNegotiate, Err: err}
	}
	if hdr[1] != 0 {
		return &StageError{Stage: StageForward, Err: &SocksReplyError{Code: hdr[1]}}
	}
	return nil
}

func socks5Encode(cmd byte, host string, port uint16) ([]byte, error) {
	req := []byte{5, cmd, 0}
	if ip, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
		ip = ip.Unmap()
		if ip.Is4() {
			req = append(req, 1)
		} else {
			req = append(req, 4)
		}
		req = append(req, ip.AsSlice()...)
	} else {
		if len(host) == 0 || len(host) > 255 {
			return nil, fmt.Errorf("socks5: host name length %d", len(host))
		}
		req = append(req, 3, byte(len(host)))
		req = append(req, host...)
	}
	return binary.BigEndian.AppendUint16(req, port), nil
}

// readSocksAddrRaw reads an address field without resolving it.
func readSocksAddrRaw(r io.Reader, atyp byte) ([]byte, error) {
	var n int
	switch atyp {
	case 1:
		n = 4 + 2
	case 4:
		n = 16 + 2
	case 3:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(r, lb); err != nil {
			return nil, err
		}
		b := make([]byte, int(lb[0])+2)
		_, err := io.ReadFull(r, b)
		return append(lb, b...), err
	default:
		return nil, fmt.Errorf("socks5: atyp %d", atyp)
	}
	b := make([]byte, n)
	_, err := io.ReadFull(r, b)
	return b, err
}

// SocksReplyError carries the RFC 1928 reply code.
type SocksReplyError struct{ Code byte }

func (e *SocksReplyError) Error() string {
	names := map[byte]string{
		1: "general failure", 2: "not allowed by ruleset", 3: "network unreachable",
		4: "host unreachable", 5: "connection refused", 6: "TTL expired",
		7: "command not supported", 8: "address type not supported",
		// Tor extensions (socks-extensions.txt)
		0xF0: "onion service descriptor not found", 0xF1: "onion service descriptor invalid",
		0xF2: "onion service introduction failed", 0xF3: "onion service rendezvous failed",
		0xF4: "onion service missing client authorization", 0xF5: "onion service wrong client authorization",
		0xF6: "onion service address invalid", 0xF7: "onion service introduction timed out",
	}
	if n, ok := names[e.Code]; ok {
		return fmt.Sprintf("socks5: %s (0x%02x)", n, e.Code)
	}
	return fmt.Sprintf("socks5: reply 0x%02x", e.Code)
}

// DialUDP opens a SOCKS5 UDP ASSOCIATE session. HTTP CONNECT cannot carry UDP.
func (d Dialer) DialUDP(ctx context.Context, dest netip.AddrPort) (net.PacketConn, error) {
	if !d.Cfg.Enabled() {
		return nil, errors.New("forced upstream is off")
	}
	if dest.Addr().Unmap().Is6() && d.ipv6Blocked() {
		return nil, ErrIPv6Blocked
	}
	if d.Cfg.Kind != KindSOCKS5 || d.Cfg.Backend != BackendNone {
		// HTTP CONNECT, SAM streams and Tor cannot carry arbitrary UDP
		// (Tor does not implement UDP ASSOCIATE). Blocked, never direct.
		return nil, ErrUDPNotSupported
	}
	return d.dialSOCKS5UDP(ctx, dest)
}

func (d Dialer) dialSOCKS5UDP(ctx context.Context, dest netip.AddrPort) (net.PacketConn, error) {
	// x/net/proxy SOCKS5 only does CONNECT (cmd 1). ASSOCIATE is ours.
	return associateUDP(ctx, d.dialProxy(ctx), d.Cfg, dest)
}

func associateUDP(ctx context.Context, dial DialFunc, cfg Config, dest netip.AddrPort) (net.PacketConn, error) {
	c, err := dial(ctx, "tcp", cfg.ProxyAddr())
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(deadline)
	} else {
		_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	}
	if err := socks5Handshake(c, cfg.Username, cfg.Password); err != nil {
		c.Close()
		return nil, err
	}
	// ASSOCIATE with DST 0.0.0.0:0
	req := []byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	if _, err := c.Write(req); err != nil {
		c.Close()
		return nil, err
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(c, hdr); err != nil {
		c.Close()
		return nil, err
	}
	if hdr[0] != 5 || hdr[1] != 0 {
		c.Close()
		return nil, fmt.Errorf("%w: reply %d", ErrUDPNotSupported, hdr[1])
	}
	relay, err := readSocksAddr(c, hdr[3])
	if err != nil {
		c.Close()
		return nil, err
	}
	_ = c.SetDeadline(time.Time{})
	udp, err := net.ListenPacket("udp", "0.0.0.0:0")
	if err != nil {
		c.Close()
		return nil, err
	}
	raddr, err := net.ResolveUDPAddr("udp", relay.String())
	if err != nil {
		udp.Close()
		c.Close()
		return nil, err
	}
	return &socksUDPConn{ctrl: c, udp: udp, relay: raddr, dest: dest}, nil
}

func socks5Handshake(c net.Conn, user, pass string) error {
	if user == "" {
		if _, err := c.Write([]byte{5, 1, 0}); err != nil {
			return err
		}
		rep := make([]byte, 2)
		if _, err := io.ReadFull(c, rep); err != nil {
			return err
		}
		if rep[0] != 5 || rep[1] != 0 {
			if rep[1] == 0xFF || rep[1] == 2 {
				return &StageError{Stage: StageAuth, Err: errors.New("socks5: proxy requires authentication")}
			}
			return &StageError{Stage: StageNegotiate, Err: fmt.Errorf("socks5: method %d rejected", rep[1])}
		}
		return nil
	}
	if _, err := c.Write([]byte{5, 1, 2}); err != nil {
		return err
	}
	rep := make([]byte, 2)
	if _, err := io.ReadFull(c, rep); err != nil {
		return err
	}
	if rep[0] != 5 {
		return &StageError{Stage: StageNegotiate, Err: fmt.Errorf("socks5: bad version %d", rep[0])}
	}
	if rep[1] != 2 {
		return &StageError{Stage: StageAuth, Err: errors.New("socks5: server rejected username auth")}
	}
	ub, pb := []byte(user), []byte(pass)
	buf := make([]byte, 0, 3+len(ub)+len(pb))
	buf = append(buf, 1, byte(len(ub)))
	buf = append(buf, ub...)
	buf = append(buf, byte(len(pb)))
	buf = append(buf, pb...)
	if _, err := c.Write(buf); err != nil {
		return err
	}
	if _, err := io.ReadFull(c, rep); err != nil {
		return err
	}
	if rep[1] != 0 {
		return &StageError{Stage: StageAuth, Err: errors.New("socks5: username/password rejected")}
	}
	return nil
}

func readSocksAddr(r io.Reader, atyp byte) (netip.AddrPort, error) {
	switch atyp {
	case 1:
		b := make([]byte, 6)
		if _, err := io.ReadFull(r, b); err != nil {
			return netip.AddrPort{}, err
		}
		ip, _ := netip.AddrFromSlice(b[:4])
		return netip.AddrPortFrom(ip, binary.BigEndian.Uint16(b[4:])), nil
	case 4:
		b := make([]byte, 18)
		if _, err := io.ReadFull(r, b); err != nil {
			return netip.AddrPort{}, err
		}
		ip, _ := netip.AddrFromSlice(b[:16])
		return netip.AddrPortFrom(ip, binary.BigEndian.Uint16(b[16:])), nil
	case 3:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(r, lb); err != nil {
			return netip.AddrPort{}, err
		}
		host := make([]byte, int(lb[0])+2)
		if _, err := io.ReadFull(r, host); err != nil {
			return netip.AddrPort{}, err
		}
		port := binary.BigEndian.Uint16(host[len(host)-2:])
		ips, err := net.LookupIP(string(host[:len(host)-2]))
		if err != nil || len(ips) == 0 {
			return netip.AddrPort{}, fmt.Errorf("socks5: bind host %q", host[:len(host)-2])
		}
		addr, _ := netip.AddrFromSlice(ips[0])
		return netip.AddrPortFrom(addr.Unmap(), port), nil
	default:
		return netip.AddrPort{}, fmt.Errorf("socks5: atyp %d", atyp)
	}
}

type socksUDPConn struct {
	ctrl  net.Conn
	udp   net.PacketConn
	relay *net.UDPAddr
	dest  netip.AddrPort
	mu    sync.Mutex
}

func (c *socksUDPConn) ReadFrom(p []byte) (int, net.Addr, error) {
	buf := make([]byte, 65535)
	n, _, err := c.udp.ReadFrom(buf)
	if err != nil {
		return 0, nil, err
	}
	if n < 4 {
		return 0, nil, io.ErrUnexpectedEOF
	}
	// RSV RSV FRAG ATYP ...
	atyp := buf[3]
	off := 4
	switch atyp {
	case 1:
		off += 4
	case 4:
		off += 16
	case 3:
		if n < 5 {
			return 0, nil, io.ErrUnexpectedEOF
		}
		off += 1 + int(buf[4])
	default:
		return 0, nil, fmt.Errorf("socks5 udp atyp %d", atyp)
	}
	off += 2
	if n < off {
		return 0, nil, io.ErrUnexpectedEOF
	}
	copied := copy(p, buf[off:n])
	return copied, addrPortAddr{c.dest}, nil
}

func (c *socksUDPConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	ap, err := asAddrPort(addr)
	if err != nil {
		ap = c.dest
	}
	hdr := socksUDPHeader(ap)
	buf := append(hdr, p...)
	c.mu.Lock()
	defer c.mu.Unlock()
	_, err = c.udp.WriteTo(buf, c.relay)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *socksUDPConn) Close() error {
	c.udp.Close()
	return c.ctrl.Close()
}

func (c *socksUDPConn) LocalAddr() net.Addr                { return c.udp.LocalAddr() }
func (c *socksUDPConn) SetDeadline(t time.Time) error      { return c.udp.SetDeadline(t) }
func (c *socksUDPConn) SetReadDeadline(t time.Time) error  { return c.udp.SetReadDeadline(t) }
func (c *socksUDPConn) SetWriteDeadline(t time.Time) error { return c.udp.SetWriteDeadline(t) }

func socksUDPHeader(dest netip.AddrPort) []byte {
	ip := dest.Addr()
	if ip.Is4() {
		out := []byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 0}
		copy(out[4:8], ip.AsSlice())
		binary.BigEndian.PutUint16(out[8:], dest.Port())
		return out
	}
	out := make([]byte, 4+16+2)
	out[3] = 4
	copy(out[4:20], ip.AsSlice())
	binary.BigEndian.PutUint16(out[20:], dest.Port())
	return out
}

func asAddrPort(a net.Addr) (netip.AddrPort, error) {
	switch t := a.(type) {
	case *net.UDPAddr:
		ip, ok := netip.AddrFromSlice(t.IP)
		if !ok {
			return netip.AddrPort{}, errors.New("bad udp addr")
		}
		return netip.AddrPortFrom(ip.Unmap(), uint16(t.Port)), nil
	case addrPortAddr:
		return t.AddrPort, nil
	default:
		return netip.ParseAddrPort(a.String())
	}
}

type addrPortAddr struct{ netip.AddrPort }

func (a addrPortAddr) Network() string { return "udp" }
func (a addrPortAddr) String() string  { return a.AddrPort.String() }

func hostOf(dest string) string {
	h, _, err := net.SplitHostPort(dest)
	if err != nil {
		return dest
	}
	return strings.Trim(h, "[]")
}

// Errors for reserved names. All are final: callers must refuse the
// flow, never retry it directly or through public DNS.
var (
	ErrReservedNoBackend = errors.New("reserved name (.onion/.i2p/local) has no matching backend; refusing instead of leaking it")
	ErrVirtualUnmapped   = errors.New("virtual address has no live name mapping (expired or invalidated); refusing")
	ErrInvalidOnion      = errors.New("invalid v3 .onion address")
	ErrNoOutproxy        = errors.New("destination is not an I2P name and no I2P outproxy is configured")
	ErrBackendNeedsName  = errors.New("backend requires a hostname destination")
)

// gateDest applies the reserved-name rules to one destination and
// returns the (possibly name-substituted) destination to dial:
//
//   - a synthetic virtual address is replaced by its name (or refused);
//   - .onion only goes to a Tor backend, .i2p only to an I2P backend;
//   - local/mesh-private names are never sent to an upstream proxy;
//   - an I2P backend refuses clearnet unless an outproxy is set.
func (d Dialer) gateDest(dest string) (string, error) {
	host, port, err := net.SplitHostPort(dest)
	if err != nil {
		return "", err
	}
	host = strings.Trim(host, "[]")
	if ip, perr := netip.ParseAddr(host); perr == nil {
		if IsVirtualAddr(ip) {
			if d.Names == nil {
				return "", ErrVirtualUnmapped
			}
			name, _, ok := d.Names.LookupVirtual(ip)
			if !ok {
				return "", ErrVirtualUnmapped
			}
			host = name
		} else {
			if d.Cfg.Backend == BackendI2PSAM && strings.TrimSpace(d.Cfg.Outproxy) == "" {
				return "", ErrNoOutproxy
			}
			return dest, nil
		}
	}
	switch c := ClassifyName(host); c {
	case NameOnionInvalid:
		return "", ErrInvalidOnion
	case NameOnion:
		if d.Cfg.Backend != BackendTor {
			return "", ErrReservedNoBackend
		}
	case NameI2P:
		if d.Cfg.Backend != BackendI2PSAM && d.Cfg.Backend != BackendI2PHTTP {
			return "", ErrReservedNoBackend
		}
	case NameLocal:
		return "", ErrReservedNoBackend
	default: // public name
		if d.Cfg.Backend == BackendI2PSAM && strings.TrimSpace(d.Cfg.Outproxy) == "" {
			return "", ErrNoOutproxy
		}
		if d.Cfg.Backend == BackendI2PHTTP && strings.TrimSpace(d.Cfg.Outproxy) == "" {
			// The router's HTTP proxy only reaches clearnet through an
			// outproxy the router itself is configured with; we cannot
			// see that setting, so the user must say it is there.
			return "", ErrNoOutproxy
		}
	}
	return net.JoinHostPort(host, port), nil
}

// dialSAM opens dest through the I2P SAM bridge, or through the
// configured outproxy (HTTP CONNECT over an I2P stream) for clearnet.
func (d Dialer) dialSAM(ctx context.Context, dest string) (net.Conn, error) {
	if d.SAM == nil {
		return nil, &StageError{Stage: StageNegotiate, Err: errors.New("i2p sam manager not configured")}
	}
	host, portStr, err := net.SplitHostPort(dest)
	if err != nil {
		return nil, err
	}
	host = strings.Trim(host, "[]")
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("bad destination port %q", portStr)
	}
	if I2PName(host) {
		return d.SAM.DialStream(ctx, host, port)
	}
	op := strings.TrimSpace(d.Cfg.Outproxy)
	if op == "" || !I2PName(op) {
		return nil, ErrNoOutproxy
	}
	c, err := d.SAM.DialStream(ctx, op, 80)
	if err != nil {
		return nil, err
	}
	pc, err := httpConnectOver(ctx, c, dest, d.Cfg.Username, d.Cfg.Password)
	if err != nil {
		c.Close()
		return nil, err
	}
	return pc, nil
}
