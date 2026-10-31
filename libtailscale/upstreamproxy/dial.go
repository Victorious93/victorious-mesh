// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"time"

	xproxy "golang.org/x/net/proxy"
)

// DialFunc opens a protected (VpnService.protect) socket to the proxy.
type DialFunc func(ctx context.Context, network, address string) (net.Conn, error)

var ErrUDPNotSupported = errors.New("upstream does not support SOCKS5 UDP ASSOCIATE")

type Dialer struct {
	Cfg    Config
	Dial   DialFunc // used to reach the proxy itself
	Direct DialFunc // used only when FailClosed is false and the proxy is down
}

func (d Dialer) dialProxy(ctx context.Context) DialFunc {
	dial := d.Dial
	if dial == nil {
		dial = defaultDial
	}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		c, err := dial(ctx, network, address)
		if err != nil {
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
	if destIsIPv6(dest) && IPv6State() == IPv6Blocked {
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
	case KindHTTP:
		c, err = d.dialHTTPConnect(ctx, dest)
	case KindSOCKS5:
		c, err = d.dialSOCKS5TCP(ctx, dest)
	default:
		return nil, fmt.Errorf("unsupported kind %q", d.Cfg.Kind)
	}
	if err == nil {
		if destIsIPv6(dest) {
			noteIPv6(IPv6ViaProxy)
		}
		if p, ok := destPort(dest); ok && p == dnsPort {
			noteDNS("tcp")
		}
		return c, nil
	}
	if p, ok := destPort(dest); ok && p == dnsPort {
		noteDNS("blocked")
	}
	if destIsIPv6(dest) {
		if ipv6RejectedByProxy(err) {
			noteIPv6(IPv6Blocked)
		}
		return nil, err
	}
	if d.Cfg.FailClosed || d.Direct == nil {
		return nil, err
	}
	return d.Direct(ctx, "tcp", dest)
}

func (d Dialer) dialHTTPConnect(ctx context.Context, dest string) (net.Conn, error) {
	c, err := d.dialProxy(ctx)(ctx, "tcp", d.Cfg.ProxyAddr())
	if err != nil {
		return nil, err
	}
	req := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: dest},
		Host:   dest,
		Header: make(http.Header),
	}
	if d.Cfg.Username != "" {
		token := base64.StdEncoding.EncodeToString([]byte(d.Cfg.Username + ":" + d.Cfg.Password))
		req.Header.Set("Proxy-Authorization", "Basic "+token)
	}
	if err := req.Write(c); err != nil {
		c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, req)
	if err != nil {
		c.Close()
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		c.Close()
		return nil, fmt.Errorf("CONNECT %s: %s", dest, resp.Status)
	}
	if br.Buffered() == 0 {
		return c, nil
	}
	return &prefixConn{Conn: c, r: br}, nil
}

type prefixConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *prefixConn) Read(p []byte) (int, error) { return c.r.Read(p) }

func (d Dialer) dialSOCKS5TCP(ctx context.Context, dest string) (net.Conn, error) {
	var auth *xproxy.Auth
	if d.Cfg.Username != "" {
		auth = &xproxy.Auth{User: d.Cfg.Username, Password: d.Cfg.Password}
	}
	forward := &ctxDialer{ctx: ctx, dial: d.dialProxy(ctx)}
	px, err := xproxy.SOCKS5("tcp", d.Cfg.ProxyAddr(), auth, forward)
	if err != nil {
		return nil, err
	}
	return px.Dial("tcp", dest)
}

type ctxDialer struct {
	ctx  context.Context
	dial DialFunc
}

func (d *ctxDialer) Dial(network, address string) (net.Conn, error) {
	return d.dial(d.ctx, network, address)
}

func (d *ctxDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return d.dial(ctx, network, address)
}

// DialUDP opens a SOCKS5 UDP ASSOCIATE session. HTTP CONNECT cannot carry UDP.
func (d Dialer) DialUDP(ctx context.Context, dest netip.AddrPort) (net.PacketConn, error) {
	if !d.Cfg.Enabled() {
		return nil, errors.New("forced upstream is off")
	}
	if dest.Addr().Unmap().Is6() && IPv6State() == IPv6Blocked {
		return nil, ErrIPv6Blocked
	}
	if d.Cfg.Kind != KindSOCKS5 {
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
			return fmt.Errorf("socks5: method %d rejected", rep[1])
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
	if rep[0] != 5 || rep[1] != 2 {
		return errors.New("socks5: server rejected username auth")
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
		return errors.New("socks5: username/password rejected")
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
