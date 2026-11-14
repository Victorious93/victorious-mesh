// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package dnstools

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

// Transport is how a resolver profile talks to its server.
type Transport string

const (
	// TransportSystem uses the servers the phone's current network
	// handed out (supplied by the app through Env.SystemServers).
	TransportSystem Transport = "system"
	TransportUDP    Transport = "udp" // plain DNS, TCP retry on truncation
	TransportTCP    Transport = "tcp"
	TransportDoT    Transport = "dot" // RFC 7858, certificate verified
	TransportDoH    Transport = "doh" // RFC 8484, certificate verified
)

// Route is the network path a profile's queries take. It is explicit
// and never changes by itself: if the route is unavailable the query
// fails, it does not fall back to another route.
type Route string

const (
	RouteDirect Route = "direct" // protected socket on Wi-Fi/cellular, outside the VPN
	RouteMesh   Route = "mesh"   // through the mesh tunnel / MagicDNS
	RouteProxy  Route = "proxy"  // through the forced upstream proxy
	RouteTor    Route = "tor"    // through the Tor backend
)

// Profile is one resolver choice.
type Profile struct {
	ID        string
	Name      string
	Transport Transport
	// Server: host[:port] for udp/tcp/dot; an https:// URL for doh.
	// Ignored for TransportSystem.
	Server string
	// ServerName overrides the TLS name checked for dot/doh. Empty means
	// the server's host. Verification can not be switched off.
	ServerName string
	// Bootstrap are literal IPs for a hostname Server. A hostname is
	// never resolved with any other DNS (system included); with no
	// bootstrap IPs it is refused unless the route itself resolves
	// names (proxy, tor).
	Bootstrap []netip.Addr
	Route     Route
	// Private marks resolvers allowed to see local / private-zone /
	// private-reverse names (mesh DNS, the network's own DNS).
	Private bool
	// Domains, if set, make this a split-DNS profile for those suffixes.
	Domains []string
}

// DialFunc dials over one route.
type DialFunc func(ctx context.Context, network, address string) (net.Conn, error)

// Env connects the package to the app.
type Env struct {
	// Dial returns the dialer for a route, or nil if that route is not
	// available right now (the query then fails, never reroutes).
	Dial func(Route) DialFunc
	// RouteResolvesNames says the route's far end resolves hostnames
	// itself (a SOCKS/CONNECT proxy, Tor), so a hostname server needs no
	// bootstrap IPs.
	RouteResolvesNames func(Route) bool
	// SystemServers are the current network's DNS servers.
	SystemServers func() []netip.AddrPort
	// Roots overrides the TLS roots (tests). nil = system roots.
	Roots *x509.CertPool
	Now   func() time.Time
}

func (e Env) now() time.Time {
	if e.Now != nil {
		return e.Now()
	}
	return time.Now()
}

// Options for one query.
type Options struct {
	DNSSEC  bool // ask for DNSSEC records (DO bit)
	NoCache bool // bypass and do not fill the cache
}

// Typed failures.
var (
	ErrReservedName   = errors.New("reserved name (.onion / .i2p): refused by public DNS profiles; it is resolved only by its own network")
	ErrPrivateName    = errors.New("private or local name: only private resolver profiles (mesh, network DNS) may see it")
	ErrNoBootstrap    = errors.New("resolver is a hostname with no bootstrap IPs; refusing to look it up with any other DNS")
	ErrRouteDown      = errors.New("the route for this resolver is not available; not falling back to another route")
	ErrBadResponse    = errors.New("response did not match the query")
	ErrNoSystemServer = errors.New("the current network provided no DNS servers")
)

// Resolver runs lookups. Safe for concurrent use.
type Resolver struct {
	env   Env
	cache *Cache
	mu    sync.RWMutex
	local LocalPolicy
}

func NewResolver(env Env) *Resolver {
	return &Resolver{env: env, cache: NewCache(2048, env.now)}
}

func (r *Resolver) Cache() *Cache { return r.cache }

// SetLocalPolicy installs local host mappings and allow/block rules and
// drops cached answers they could contradict.
func (r *Resolver) SetLocalPolicy(p LocalPolicy) {
	r.mu.Lock()
	r.local = p
	r.mu.Unlock()
	r.cache.Clear()
}

// DNSSECReport says what is known about DNSSEC for one answer. This
// app does not validate signatures; it only reports what the resolver
// claimed and whether signatures came back.
type DNSSECReport struct {
	Requested         bool   `json:"requested"`
	ResolverClaimsAD  bool   `json:"resolverClaimsAD"`
	RRSIGs            int    `json:"rrsigs"`
	ValidatedLocally  bool   `json:"validatedLocally"` // always false: not implemented
	PathAuthenticated bool   `json:"pathAuthenticated"`
	Summary           string `json:"summary"`
}

// Result is everything the UI shows and exports for one lookup.
type Result struct {
	Name       string       `json:"name"`
	Type       QType        `json:"type"`
	Profile    string       `json:"profile"`
	Transport  Transport    `json:"transport"`
	Route      Route        `json:"route"`
	Server     string       `json:"server"`
	RCode      string       `json:"rcode,omitempty"`
	Flags      Flags        `json:"flags"`
	Answer     []RR         `json:"answer,omitempty"`
	Authority  []RR         `json:"authority,omitempty"`
	Additional []RR         `json:"additional,omitempty"`
	Millis     float64      `json:"ms"`
	Cache      string       `json:"cache"` // miss | hit | negative-hit | local | blocked | bypass
	CacheTTL   uint32       `json:"cacheTTL,omitempty"`
	DNSSEC     DNSSECReport `json:"dnssec"`
	RetriedTCP bool         `json:"retriedTCP,omitempty"`
	EDE        int          `json:"ede,omitempty"`
	Error      string       `json:"error,omitempty"`
	At         time.Time    `json:"at"`
}

// OK reports a usable response (NOERROR or NXDOMAIN).
func (r *Result) OK() bool { return r.Error == "" && (r.RCode == "NOERROR" || r.RCode == "NXDOMAIN") }

func (p Profile) endpoint() string {
	switch p.Transport {
	case TransportSystem:
		return "network DNS"
	default:
		return p.Server
	}
}

// Query asks profile p for name/type.
func (r *Resolver) Query(ctx context.Context, p Profile, name string, qt QType, opt Options) *Result {
	start := time.Now()
	name = upstreamproxy.NormalizeName(name)
	res := &Result{Name: name, Type: qt, Profile: p.Name, Transport: p.Transport, Route: p.Route, Server: p.endpoint(), Cache: "miss", At: r.env.now(), EDE: -1}
	finish := func() *Result {
		res.Millis = float64(time.Since(start).Microseconds()) / 1000
		return res
	}
	fail := func(err error) *Result {
		res.Error = err.Error()
		return finish()
	}
	if _, err := ParseQType(string(qt)); err != nil {
		return fail(err)
	}
	if name == "" {
		return fail(errors.New("empty name"))
	}
	// 1. Local host mappings and block rules come first and need no network.
	r.mu.RLock()
	local := r.local
	r.mu.RUnlock()
	if m, kind := local.Apply(name, qt); m != nil {
		res.Cache = kind
		fill(res, m)
		res.DNSSEC = dnssecReport(opt.DNSSEC, m, false)
		return finish()
	}
	// 2. Reserved / private names never reach a resolver that must not see them.
	if err := nameGuard(p, name, qt); err != nil {
		return fail(err)
	}
	// 3. Cache (profile- and generation-scoped).
	key := cacheKey{profile: p.ID, name: name, qt: qt, dnssec: opt.DNSSEC}
	if !opt.NoCache {
		if m, neg, ttl, ok := r.cache.Get(key); ok {
			res.Cache = "hit"
			if neg {
				res.Cache = "negative-hit"
			}
			res.CacheTTL = ttl
			fill(res, m)
			res.DNSSEC = dnssecReport(opt.DNSSEC, m, pathAuthenticated(p))
			return finish()
		}
	} else {
		res.Cache = "bypass"
	}
	// 4. Real exchange.
	q, err := BuildQuery(randID(), name, qt, opt.DNSSEC)
	if err != nil {
		return fail(err)
	}
	resp, retried, err := r.exchange(ctx, p, q)
	res.RetriedTCP = retried
	if err != nil {
		return fail(err)
	}
	m, err := ParseResponse(resp)
	if err != nil {
		return fail(err)
	}
	if m.ID != mustID(q) {
		return fail(ErrBadResponse)
	}
	fill(res, m)
	res.DNSSEC = dnssecReport(opt.DNSSEC, m, pathAuthenticated(p))
	if !opt.NoCache {
		r.cache.Put(key, m)
	}
	return finish()
}

func mustID(b []byte) uint16 { id, _ := QueryID(b); return id }

func randID() uint16 {
	var b [2]byte
	_, _ = rand.Read(b[:])
	return binary.BigEndian.Uint16(b[:])
}

func fill(res *Result, m *Message) {
	res.RCode, res.Flags, res.Answer, res.Authority, res.Additional, res.EDE = m.RCode, m.Flags, m.Answer, m.Authority, m.Additional, m.EDE
}

func pathAuthenticated(p Profile) bool {
	// AD is only worth something if nobody on the path could have set it.
	return p.Transport == TransportDoT || p.Transport == TransportDoH
}

func dnssecReport(requested bool, m *Message, authPath bool) DNSSECReport {
	rep := DNSSECReport{Requested: requested, ResolverClaimsAD: m.Flags.AD, RRSIGs: m.RRSIGs, PathAuthenticated: authPath}
	switch {
	case !requested:
		rep.Summary = "DNSSEC was not requested."
	case m.Flags.AD && authPath:
		rep.Summary = "The resolver claims it validated this answer (AD). This app does not validate DNSSEC itself; the claim arrived over an authenticated encrypted channel."
	case m.Flags.AD:
		rep.Summary = "The resolver claims it validated this answer (AD), but the path to it is not authenticated, so the claim could be forged. This app does not validate DNSSEC itself."
	case m.RRSIGs > 0:
		rep.Summary = "Signatures were returned but the resolver did not claim validation. Not validated by this app."
	default:
		rep.Summary = "No DNSSEC evidence: the zone may be unsigned or the resolver does not validate. Not validated by this app."
	}
	return rep
}

// nameGuard enforces what each profile may be asked.
func nameGuard(p Profile, name string, qt QType) error {
	switch upstreamproxy.ClassifyName(name) {
	case upstreamproxy.NameOnion, upstreamproxy.NameOnionInvalid, upstreamproxy.NameI2P:
		return ErrReservedName
	case upstreamproxy.NameLocal:
		if !p.Private {
			return ErrPrivateName
		}
	}
	if qt == TypePTR {
		if ip, ok := parseArpa(name); ok && privateAddr(ip) && !p.Private {
			return ErrPrivateName
		}
	}
	return nil
}

func privateAddr(ip netip.Addr) bool {
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() ||
		netip.MustParsePrefix("100.64.0.0/10").Contains(ip) || netip.MustParsePrefix("fd7a:115c:a1e0::/48").Contains(ip)
}

// parseArpa reads an in-addr.arpa / ip6.arpa name back into an address.
func parseArpa(name string) (netip.Addr, bool) {
	n := upstreamproxy.NormalizeName(name)
	if s, ok := strings.CutSuffix(n, ".in-addr.arpa"); ok {
		p := strings.Split(s, ".")
		if len(p) != 4 {
			return netip.Addr{}, false
		}
		var b [4]byte
		for i := range p {
			v, err := strconv.Atoi(p[3-i])
			if err != nil || v < 0 || v > 255 {
				return netip.Addr{}, false
			}
			b[i] = byte(v)
		}
		return netip.AddrFrom4(b), true
	}
	if s, ok := strings.CutSuffix(n, ".ip6.arpa"); ok {
		p := strings.Split(s, ".")
		if len(p) != 32 {
			return netip.Addr{}, false
		}
		var b [16]byte
		for i := 0; i < 32; i++ {
			v, err := strconv.ParseUint(p[31-i], 16, 8)
			if err != nil || v > 15 {
				return netip.Addr{}, false
			}
			b[i/2] |= byte(v) << (4 * (1 - uint(i%2)))
		}
		return netip.AddrFrom16(b), true
	}
	return netip.Addr{}, false
}

// -------------------------------------------------------------- transport

func (r *Resolver) dialer(p Profile) (DialFunc, error) {
	if r.env.Dial == nil {
		return nil, ErrRouteDown
	}
	d := r.env.Dial(p.Route)
	if d == nil {
		return nil, fmt.Errorf("%w (%s)", ErrRouteDown, p.Route)
	}
	return d, nil
}

type target struct {
	hosts []string // dial addresses host:port, tried in order
	name  string   // TLS / HTTP host name
	path  string   // DoH URL
}

func (r *Resolver) servers(p Profile, defPort string) ([]string, string, error) {
	var raw []string
	if p.Transport == TransportSystem {
		if r.env.SystemServers == nil {
			return nil, "", ErrNoSystemServer
		}
		for _, ap := range r.env.SystemServers() {
			raw = append(raw, ap.String())
		}
		if len(raw) == 0 {
			return nil, "", ErrNoSystemServer
		}
		return raw, "", nil
	}
	host, port, err := net.SplitHostPort(p.Server)
	if err != nil {
		host, port = strings.Trim(p.Server, "[]"), defPort
	}
	if host == "" {
		return nil, "", errors.New("resolver server is empty")
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return []string{net.JoinHostPort(ip.String(), port)}, ip.String(), nil
	}
	if len(p.Bootstrap) > 0 {
		for _, ip := range p.Bootstrap {
			raw = append(raw, net.JoinHostPort(ip.String(), port))
		}
		return raw, host, nil
	}
	if r.env.RouteResolvesNames != nil && r.env.RouteResolvesNames(p.Route) {
		return []string{net.JoinHostPort(host, port)}, host, nil
	}
	return nil, "", ErrNoBootstrap
}

func (r *Resolver) exchange(ctx context.Context, p Profile, q []byte) (resp []byte, retried bool, err error) {
	dial, err := r.dialer(p)
	if err != nil {
		return nil, false, err
	}
	if _, has := ctx.Deadline(); !has {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, 8*time.Second)
		defer cancel()
	}
	switch p.Transport {
	case TransportSystem, TransportUDP:
		addrs, _, err := r.servers(p, "53")
		if err != nil {
			return nil, false, err
		}
		var last error
		for _, a := range addrs {
			resp, err := exchangeUDP(ctx, dial, a, q)
			if err == nil {
				if m, perr := ParseResponse(resp); perr == nil && m.Flags.TC {
					if tr, terr := exchangeTCP(ctx, dial, a, q, nil, ""); terr == nil {
						return tr, true, nil
					}
				}
				return resp, false, nil
			}
			last = err
		}
		return nil, false, last
	case TransportTCP:
		addrs, _, err := r.servers(p, "53")
		if err != nil {
			return nil, false, err
		}
		var last error
		for _, a := range addrs {
			resp, err := exchangeTCP(ctx, dial, a, q, nil, "")
			if err == nil {
				return resp, false, nil
			}
			last = err
		}
		return nil, false, last
	case TransportDoT:
		addrs, host, err := r.servers(p, "853")
		if err != nil {
			return nil, false, err
		}
		sni := firstNonEmpty(p.ServerName, host)
		cfg := r.tlsConfig(sni, []string{"dot"})
		var last error
		for _, a := range addrs {
			resp, err := exchangeTCP(ctx, dial, a, q, cfg, sni)
			if err == nil {
				return resp, false, nil
			}
			last = err
		}
		return nil, false, last
	case TransportDoH:
		resp, err := r.exchangeDoH(ctx, dial, p, q)
		return resp, false, err
	}
	return nil, false, fmt.Errorf("unknown transport %q", p.Transport)
}

func firstNonEmpty(a ...string) string {
	for _, s := range a {
		if s != "" {
			return s
		}
	}
	return ""
}

func (r *Resolver) tlsConfig(serverName string, protos []string) *tls.Config {
	return &tls.Config{ServerName: serverName, RootCAs: r.env.Roots, MinVersion: tls.VersionTLS12, NextProtos: protos}
}

func exchangeUDP(ctx context.Context, dial DialFunc, addr string, q []byte) ([]byte, error) {
	c, err := dial(ctx, "udp", addr)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	if _, err := c.Write(q); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return nil, err
		}
		if n >= 2 && buf[0] == q[0] && buf[1] == q[1] { // ignore spoofed/stale IDs
			return append([]byte(nil), buf[:n]...), nil
		}
	}
}

func exchangeTCP(ctx context.Context, dial DialFunc, addr string, q []byte, cfg *tls.Config, sni string) ([]byte, error) {
	c, err := dial(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	var rw io.ReadWriter = c
	if cfg != nil {
		tc := tls.Client(c, cfg)
		if err := tc.HandshakeContext(ctx); err != nil {
			return nil, fmt.Errorf("TLS to %s failed (not retried without TLS): %w", sni, err)
		}
		rw = tc
	}
	if err := upstreamproxy.WriteTCPMessage(rw, q); err != nil {
		return nil, err
	}
	resp, err := upstreamproxy.ReadTCPMessage(rw)
	if err != nil {
		return nil, err
	}
	if len(resp) < 2 || resp[0] != q[0] || resp[1] != q[1] {
		return nil, ErrBadResponse
	}
	return resp, nil
}

func (r *Resolver) exchangeDoH(ctx context.Context, dial DialFunc, p Profile, q []byte) ([]byte, error) {
	u, err := url.Parse(p.Server)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" {
		return nil, errors.New("DoH server must be an https:// URL (plain http is never used)")
	}
	port := u.Port()
	if port == "" {
		port = "443"
	}
	var addrs []string
	if ip, err := netip.ParseAddr(u.Hostname()); err == nil {
		addrs = []string{net.JoinHostPort(ip.String(), port)}
	} else if len(p.Bootstrap) > 0 {
		for _, ip := range p.Bootstrap {
			addrs = append(addrs, net.JoinHostPort(ip.String(), port))
		}
	} else if r.env.RouteResolvesNames != nil && r.env.RouteResolvesNames(p.Route) {
		addrs = []string{net.JoinHostPort(u.Hostname(), port)}
	} else {
		return nil, ErrNoBootstrap
	}
	sni := firstNonEmpty(p.ServerName, u.Hostname())
	var last error
	for _, a := range addrs {
		tr := &http.Transport{
			DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
				return dial(ctx, "tcp", a) // pinned to the bootstrap address
			},
			TLSClientConfig:       r.tlsConfig(sni, nil),
			ForceAttemptHTTP2:     true,
			TLSHandshakeTimeout:   8 * time.Second,
			ResponseHeaderTimeout: 8 * time.Second,
			DisableKeepAlives:     true,
		}
		resp, err := doHRequest(ctx, tr, u, q)
		tr.CloseIdleConnections()
		if err == nil {
			return resp, nil
		}
		last = err
	}
	return nil, last
}

func doHRequest(ctx context.Context, tr *http.Transport, u *url.URL, q []byte) ([]byte, error) {
	// RFC 8484: ID SHOULD be 0 for cache friendliness; we keep our ID and
	// match on it, which every server must echo.
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(q))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := (&http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}).Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH server answered %s", resp.Status)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/dns-message") {
		return nil, fmt.Errorf("DoH server sent %q, not a DNS message", ct)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, err
	}
	if len(body) < 2 || body[0] != q[0] || body[1] != q[1] {
		return nil, ErrBadResponse
	}
	return body, nil
}

// PickProfile chooses among split-DNS profiles: the one whose Domains
// has the longest suffix match for name; otherwise def.
func PickProfile(profiles []Profile, def Profile, name string) Profile {
	name = upstreamproxy.NormalizeName(name)
	best, bestLen := def, -1
	for _, p := range profiles {
		for _, d := range p.Domains {
			d = upstreamproxy.NormalizeName(strings.TrimPrefix(d, "."))
			if (name == d || strings.HasSuffix(name, "."+d)) && len(d) > bestLen {
				best, bestLen = p, len(d)
			}
		}
	}
	return best
}

// SortedIDs is a small helper for stable output.
func SortedIDs(m map[string]Profile) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
