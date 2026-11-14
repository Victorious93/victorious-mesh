// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package netx is the pure-Go service layer between the Android app
// (through thin gomobile wrappers in package libtailscale) and the
// proxy / Tor / I2P / DNS packages. Everything is configured through
// JSON strings so the gomobile surface stays small; the Android side
// supplies the protected-socket dialer.
package netx

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/dnstools"
	"github.com/tailscale/tailscale-android/libtailscale/leakcheck"
	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

// Env is what the platform provides.
type Env struct {
	// ProtectedDial opens a socket outside the VPN (VpnService.protect).
	ProtectedDial upstreamproxy.DialFunc
	// SystemServers are the current underlying network's DNS servers.
	SystemServers func() []netip.AddrPort
	// Reconfigure rebuilds the VPN routes after the proxy changes.
	Reconfigure func()
}

// ProxyJSON is the wire form of one proxy / backend profile.
type ProxyJSON struct {
	Kind       string `json:"kind"` // http | https | socks5 | sam
	Host       string `json:"host"`
	Port       int32  `json:"port"`
	Username   string `json:"username,omitempty"`
	Password   string `json:"password,omitempty"`
	FailClosed *bool  `json:"failClosed,omitempty"` // default true
	ServerName string `json:"serverName,omitempty"`
	Backend    string `json:"backend,omitempty"` // "" | tor | i2p-sam | i2p-http
	Outproxy   string `json:"outproxy,omitempty"`
	// SAMStartupSeconds bounds how long an I2P session may take to come up.
	SAMStartupSeconds int `json:"samStartupSeconds,omitempty"`
}

// Config converts and validates.
func (p ProxyJSON) Config() (upstreamproxy.Config, error) {
	k, err := upstreamproxy.ParseKind(p.Kind)
	if err != nil {
		return upstreamproxy.Config{}, err
	}
	cfg := upstreamproxy.Config{
		Kind: k, Host: strings.TrimSpace(p.Host), Port: p.Port, Username: p.Username, Password: p.Password,
		FailClosed: p.FailClosed == nil || *p.FailClosed, ServerName: p.ServerName,
		Backend: upstreamproxy.Backend(p.Backend), Outproxy: p.Outproxy,
	}
	if err := upstreamproxy.Validate(cfg); err != nil {
		return cfg, err
	}
	if !cfg.Enabled() {
		return cfg, errors.New("incomplete proxy: kind, host and a port from 1 to 65535 are required")
	}
	return cfg, nil
}

// Controller owns the shared name map, the I2P sessions and the DNS tools.
type Controller struct {
	mu      sync.Mutex
	env     Env
	vmap    *upstreamproxy.VMap
	gate    *upstreamproxy.NameGate
	sams    map[string]*upstreamproxy.SAMManager
	forced  upstreamproxy.Config
	resolve *dnstools.Resolver
	hist    *dnstools.History
	guard   bool // always-on reserved-name DNS guard
	samWait time.Duration
}

// Shared is the process-wide instance used by the Android glue.
var Shared = New()

func New() *Controller {
	c := &Controller{
		vmap: upstreamproxy.NewVMap(2048, 10*time.Minute, nil),
		sams: map[string]*upstreamproxy.SAMManager{},
		hist: dnstools.NewHistory(),
	}
	c.gate = &upstreamproxy.NameGate{Map: c.vmap, Tor: c.torArmed, I2P: c.i2pArmed}
	c.resolve = dnstools.NewResolver(c.dnsEnv())
	return c
}

func (c *Controller) SetEnv(e Env) {
	c.mu.Lock()
	c.env = e
	c.mu.Unlock()
}

func (c *Controller) protected() upstreamproxy.DialFunc {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.env.ProtectedDial
}

// Gate is the reserved-name DNS gate for the TUN.
func (c *Controller) Gate() *upstreamproxy.NameGate { return c.gate }

// GuardEnabled reports whether the reserved-name guard should inspect
// DNS even when no proxy is configured.
func (c *Controller) GuardEnabled() bool { c.mu.Lock(); defer c.mu.Unlock(); return c.guard }

// SetGuard turns the always-on .onion/.i2p DNS guard on or off.
func (c *Controller) SetGuard(on bool) { c.mu.Lock(); c.guard = on; c.mu.Unlock() }

func (c *Controller) torArmed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.forced.Enabled() && c.forced.Backend == upstreamproxy.BackendTor
}

func (c *Controller) i2pArmed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.forced.Enabled() && (c.forced.Backend == upstreamproxy.BackendI2PSAM || c.forced.Backend == upstreamproxy.BackendI2PHTTP)
}

// BackendArmed reports which overlay backend the device-wide profile uses.
func (c *Controller) BackendArmed() string {
	switch {
	case c.torArmed():
		return "tor"
	case c.i2pArmed():
		return "i2p"
	}
	return ""
}

func (c *Controller) samFor(cfg upstreamproxy.Config) *upstreamproxy.SAMManager {
	if cfg.Kind != upstreamproxy.KindSAM {
		return nil
	}
	key := cfg.ProxyAddr()
	c.mu.Lock()
	defer c.mu.Unlock()
	if m, ok := c.sams[key]; ok {
		return m
	}
	dial := c.env.ProtectedDial
	m := upstreamproxy.NewSAMManager(cfg.ProxyAddr(), dial, upstreamproxy.SAMOptions{StartupTimeout: c.samWait, Outproxy: cfg.Outproxy})
	c.sams[key] = m
	return m
}

// DialerFor builds the flow dialer for a profile. quiet keeps a per-app
// proxy from touching the device-wide forced-proxy status.
func (c *Controller) DialerFor(cfg upstreamproxy.Config, quiet bool) upstreamproxy.Dialer {
	d := upstreamproxy.Dialer{Cfg: cfg, Dial: c.protected(), Quiet: quiet, Names: c.vmap, SAM: c.samFor(cfg)}
	return d
}

// SetForced installs the device-wide profile. It also invalidates every
// virtual-address mapping and closed I2P session left from before, so a
// stricter or different policy can never be satisfied by stale state.
func (c *Controller) SetForced(js string) error {
	var pj ProxyJSON
	if err := json.Unmarshal([]byte(js), &pj); err != nil {
		return fmt.Errorf("bad proxy json: %w", err)
	}
	cfg, err := pj.Config()
	if err != nil {
		return err
	}
	c.mu.Lock()
	if pj.SAMStartupSeconds > 0 {
		c.samWait = time.Duration(pj.SAMStartupSeconds) * time.Second
	}
	changed := c.forced != cfg
	c.forced = cfg
	c.mu.Unlock()
	if changed {
		c.invalidateAll()
	}
	if err := upstreamproxy.Set(cfg); err != nil {
		return err
	}
	if changed {
		c.notifyReconfigure()
	}
	return nil
}

// ClearForced turns the device-wide profile off and drops all overlay state.
func (c *Controller) ClearForced() {
	c.mu.Lock()
	c.forced = upstreamproxy.Config{}
	c.mu.Unlock()
	c.invalidateAll()
	upstreamproxy.Clear()
	c.notifyReconfigure()
}

func (c *Controller) notifyReconfigure() {
	c.mu.Lock()
	fn := c.env.Reconfigure
	c.mu.Unlock()
	if fn != nil {
		fn()
	}
}

// InvalidateAll is called on any policy change that could make old
// state unsafe: virtual addresses, I2P sessions and DNS caches go.
func (c *Controller) InvalidateAll() { c.invalidateAll() }

func (c *Controller) invalidateAll() {
	c.vmap.Reset()
	c.mu.Lock()
	for k, m := range c.sams {
		m.Close()
		delete(c.sams, k)
	}
	c.mu.Unlock()
	c.resolve.Cache().Bump()
	c.resolve.Cache().Clear()
}

// VMap exposes the mapping for stats.
func (c *Controller) MappingCount() int { return c.vmap.Len() }

// ParseURI reads a proxy URI (http, https, socks5, socks5h, sam) with the
// same strict rules as everywhere else: explicit port 1-65535, bracketed
// IPv6, percent-decoded credentials. The reply carries the password only
// as "hasPassword"; the caller already holds the URI it typed.
func (c *Controller) ParseURI(raw string) string {
	cfg, err := upstreamproxy.ParseProxyURI(raw)
	if err != nil {
		return errJSON(err.Error())
	}
	return mustJSON(map[string]any{
		"kind": string(cfg.Kind), "host": cfg.Host, "port": cfg.Port, "username": cfg.Username,
		"password": cfg.Password, "backend": string(cfg.Backend),
	})
}

// RedactURI renders a profile as a URI with the password removed.
func (c *Controller) RedactURI(proxyJSON string) string {
	var pj ProxyJSON
	if err := json.Unmarshal([]byte(proxyJSON), &pj); err != nil {
		return ""
	}
	cfg, err := pj.Config()
	if err != nil {
		return ""
	}
	return upstreamproxy.RedactProxyURI(cfg, true)
}

// ----------------------------------------------------------- proxy test

// TargetJSON selects what the forwarding stage requests.
type TargetJSON struct {
	Host string `json:"host"`
	Port int    `json:"port"`
	TLS  bool   `json:"tls"`
	Path string `json:"path,omitempty"`
	Tor  bool   `json:"tor,omitempty"`
}

// ProxyTest runs the staged connection test and returns the report JSON.
func (c *Controller) ProxyTest(proxyJSON, targetJSON string) string {
	var pj ProxyJSON
	var tj TargetJSON
	if err := json.Unmarshal([]byte(proxyJSON), &pj); err != nil {
		return errJSON("bad proxy json: " + err.Error())
	}
	if err := json.Unmarshal([]byte(targetJSON), &tj); err != nil {
		return errJSON("bad target json: " + err.Error())
	}
	cfg, err := pj.Config()
	if err != nil {
		return errJSON(err.Error())
	}
	if tj.Host == "" {
		return errJSON("a test target host is required")
	}
	d := c.DialerFor(cfg, true)
	ctx, cancel := context.WithTimeout(context.Background(), c.testTimeout(cfg))
	defer cancel()
	rep := upstreamproxy.RunTest(ctx, d, upstreamproxy.TestTarget{Host: tj.Host, Port: tj.Port, TLS: tj.TLS, Path: tj.Path, Tor: tj.Tor})
	// Temporary I2P sessions made for a test do not outlive it.
	if cfg.Kind == upstreamproxy.KindSAM {
		c.mu.Lock()
		if m, ok := c.sams[cfg.ProxyAddr()]; ok && c.forced.ProxyAddr() != cfg.ProxyAddr() {
			m.Close()
			delete(c.sams, cfg.ProxyAddr())
		}
		c.mu.Unlock()
	}
	return mustJSON(rep)
}

func (c *Controller) testTimeout(cfg upstreamproxy.Config) time.Duration {
	if cfg.Kind == upstreamproxy.KindSAM || cfg.Backend != upstreamproxy.BackendNone {
		return 5 * time.Minute // overlay networks need real startup time
	}
	return 30 * time.Second
}

// I2PLookup asks the router's address book (SAM NAMING LOOKUP) for a
// name. It is a naming diagnostic only and never touches public DNS.
func (c *Controller) I2PLookup(proxyJSON, name string) string {
	var pj ProxyJSON
	if err := json.Unmarshal([]byte(proxyJSON), &pj); err != nil {
		return errJSON("bad proxy json: " + err.Error())
	}
	cfg, err := pj.Config()
	if err != nil {
		return errJSON(err.Error())
	}
	if cfg.Kind != upstreamproxy.KindSAM {
		return errJSON("I2P name lookup needs a SAM profile")
	}
	if upstreamproxy.ClassifyName(name) != upstreamproxy.NameI2P {
		return errJSON("not an I2P name (.i2p / .b32.i2p)")
	}
	m := upstreamproxy.NewSAMManager(cfg.ProxyAddr(), c.protected(), upstreamproxy.SAMOptions{})
	defer m.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	v, err := m.Lookup(ctx, name)
	if err != nil {
		return mustJSON(map[string]any{"name": name, "ok": false, "error": err.Error()})
	}
	if len(v) > 24 {
		v = v[:24] + "…" // destination keys are long; show a prefix
	}
	return mustJSON(map[string]any{"name": name, "ok": true, "destination": v, "via": "router address book (SAM)"})
}

// ------------------------------------------------------------------ DNS

func (c *Controller) dnsEnv() dnstools.Env {
	return dnstools.Env{
		Dial: func(r dnstools.Route) dnstools.DialFunc {
			switch r {
			case dnstools.RouteDirect:
				if d := c.protected(); d != nil {
					return dnstools.DialFunc(d)
				}
				return nil
			case dnstools.RouteMesh:
				// The app's own sockets enter the VPN, so a plain dial
				// reaches the mesh resolver / split-DNS exactly as apps do.
				return func(ctx context.Context, n, a string) (net.Conn, error) {
					return (&net.Dialer{}).DialContext(ctx, n, a)
				}
			case dnstools.RouteProxy, dnstools.RouteTor:
				c.mu.Lock()
				cfg := c.forced
				c.mu.Unlock()
				if !cfg.Enabled() || (r == dnstools.RouteTor && cfg.Backend != upstreamproxy.BackendTor) {
					return nil
				}
				d := c.DialerFor(cfg, true)
				return func(ctx context.Context, network, addr string) (net.Conn, error) {
					if !strings.HasPrefix(network, "tcp") {
						return nil, upstreamproxy.ErrUDPNotSupported // UDP resolvers over a proxy: use tcp/dot/doh
					}
					return d.DialTCP(ctx, addr)
				}
			}
			return nil
		},
		RouteResolvesNames: func(r dnstools.Route) bool { return r == dnstools.RouteProxy || r == dnstools.RouteTor },
		SystemServers: func() []netip.AddrPort {
			c.mu.Lock()
			fn := c.env.SystemServers
			c.mu.Unlock()
			if fn == nil {
				return nil
			}
			return fn()
		},
	}
}

// ProfileJSON is the wire form of a resolver profile.
type ProfileJSON struct {
	ID         string   `json:"id"`
	Name       string   `json:"name"`
	Transport  string   `json:"transport"`
	Server     string   `json:"server"`
	ServerName string   `json:"serverName,omitempty"`
	Bootstrap  []string `json:"bootstrap,omitempty"`
	Route      string   `json:"route"`
	Private    bool     `json:"private,omitempty"`
	Domains    []string `json:"domains,omitempty"`
}

func (p ProfileJSON) profile() (dnstools.Profile, error) {
	out := dnstools.Profile{ID: p.ID, Name: p.Name, Transport: dnstools.Transport(p.Transport), Server: p.Server,
		ServerName: p.ServerName, Route: dnstools.Route(p.Route), Private: p.Private, Domains: p.Domains}
	if out.ID == "" {
		out.ID = out.Name
	}
	switch out.Transport {
	case dnstools.TransportSystem, dnstools.TransportUDP, dnstools.TransportTCP, dnstools.TransportDoT, dnstools.TransportDoH:
	default:
		return out, fmt.Errorf("unknown transport %q", p.Transport)
	}
	switch out.Route {
	case dnstools.RouteDirect, dnstools.RouteMesh, dnstools.RouteProxy, dnstools.RouteTor:
	default:
		return out, fmt.Errorf("unknown route %q: choose direct, mesh, proxy or tor", p.Route)
	}
	for _, b := range p.Bootstrap {
		a, err := netip.ParseAddr(strings.TrimSpace(b))
		if err != nil {
			return out, fmt.Errorf("bootstrap %q is not an IP address", b)
		}
		out.Bootstrap = append(out.Bootstrap, a)
	}
	return out, nil
}

// QueryOpts for DNSQuery.
type QueryOpts struct {
	DNSSEC  bool `json:"dnssec"`
	NoCache bool `json:"noCache"`
}

func (c *Controller) DNSQuery(profileJSON, name, qtype, optsJSON string) string {
	var pj ProfileJSON
	if err := json.Unmarshal([]byte(profileJSON), &pj); err != nil {
		return errJSON("bad profile json: " + err.Error())
	}
	p, err := pj.profile()
	if err != nil {
		return errJSON(err.Error())
	}
	qt, err := dnstools.ParseQType(qtype)
	if err != nil {
		return errJSON(err.Error())
	}
	var o QueryOpts
	if optsJSON != "" {
		_ = json.Unmarshal([]byte(optsJSON), &o)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	res := c.resolve.Query(ctx, p, name, qt, dnstools.Options{DNSSEC: o.DNSSEC, NoCache: o.NoCache})
	c.hist.Add(res)
	return mustJSON(textual(res))
}

// withText adds the dig-like rendering so the UI can show and copy it
// without re-implementing the format.
type withText struct {
	*dnstools.Result
	Text string `json:"text"`
}

func textual(r *dnstools.Result) withText { return withText{Result: r, Text: r.Text()} }

// DNSReverse resolves an IPv4/IPv6 address to its PTR name.
func (c *Controller) DNSReverse(profileJSON, ip string) string {
	a, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return errJSON("not an IP address")
	}
	return c.DNSQuery(profileJSON, dnstools.ReverseName(a), "PTR", "")
}

func (c *Controller) DNSCompare(profilesJSON, name, qtype string) string {
	var pjs []ProfileJSON
	if err := json.Unmarshal([]byte(profilesJSON), &pjs); err != nil {
		return errJSON("bad profiles json: " + err.Error())
	}
	if len(pjs) < 2 || len(pjs) > 8 {
		return errJSON("choose 2 to 8 resolver profiles to compare")
	}
	var ps []dnstools.Profile
	for _, pj := range pjs {
		p, err := pj.profile()
		if err != nil {
			return errJSON(err.Error())
		}
		ps = append(ps, p)
	}
	qt, err := dnstools.ParseQType(qtype)
	if err != nil {
		return errJSON(err.Error())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmp := c.resolve.Compare(ctx, ps, name, qt, dnstools.Options{})
	view := struct {
		Results []withText `json:"results"`
		Agree   bool       `json:"agree"`
		Fastest string     `json:"fastest,omitempty"`
		Summary string     `json:"summary"`
	}{Agree: cmp.Agree, Fastest: cmp.Fastest, Summary: cmp.Summary}
	for _, r := range cmp.Results {
		c.hist.Add(r)
		view.Results = append(view.Results, textual(r))
	}
	return mustJSON(view)
}

func (c *Controller) DNSLatency(profileJSON, name, qtype string, samples int) string {
	var pj ProfileJSON
	if err := json.Unmarshal([]byte(profileJSON), &pj); err != nil {
		return errJSON("bad profile json: " + err.Error())
	}
	p, err := pj.profile()
	if err != nil {
		return errJSON(err.Error())
	}
	qt, err := dnstools.ParseQType(qtype)
	if err != nil {
		return errJSON(err.Error())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	return mustJSON(c.resolve.Latency(ctx, p, name, qt, samples))
}

// LocalPolicyJSON is the user's host mappings and allow/block rules.
type LocalPolicyJSON struct {
	Hosts map[string][]string `json:"hosts"`
	Allow []string            `json:"allow"`
	Block []string            `json:"block"`
}

func (c *Controller) DNSSetLocalPolicy(js string) error {
	var lp LocalPolicyJSON
	if err := json.Unmarshal([]byte(js), &lp); err != nil {
		return err
	}
	pol := dnstools.LocalPolicy{Hosts: map[string][]netip.Addr{}, Allow: lp.Allow, Block: lp.Block}
	for name, ips := range lp.Hosts {
		n := upstreamproxy.NormalizeName(name)
		if upstreamproxy.ClassifyName(n).Reserved() && upstreamproxy.ClassifyName(n) != upstreamproxy.NameLocal {
			return fmt.Errorf("%q is a reserved overlay name and cannot be mapped to an address", name)
		}
		for _, s := range ips {
			a, err := netip.ParseAddr(strings.TrimSpace(s))
			if err != nil {
				return fmt.Errorf("%q: %q is not an IP address", name, s)
			}
			pol.Hosts[n] = append(pol.Hosts[n], a)
		}
	}
	c.resolve.SetLocalPolicy(pol)
	return nil
}

func (c *Controller) DNSClearCache()    { c.resolve.Cache().Clear() }
func (c *Controller) DNSCacheSize() int { return c.resolve.Cache().Len() }

func (c *Controller) DNSHistoryEnable(on bool) { c.hist.SetEnabled(on) }
func (c *Controller) DNSHistoryClear()         { c.hist.Clear() }
func (c *Controller) DNSHistoryJSON() string   { return mustJSON(c.hist.Items()) }

// ----------------------------------------------------------- leak check

// LeakInputsJSON is ConfigInputs plus an optional observation service.
type LeakRequest struct {
	Config   leakcheck.ConfigInputs `json:"config"`
	Observer *struct {
		Zone     string   `json:"zone"`
		Endpoint string   `json:"endpoint"`
		Expected []string `json:"expected"`
	} `json:"observer,omitempty"`
	// Profile is the resolver path under test (what apps use).
	Profile *ProfileJSON `json:"profile,omitempty"`
}

func (c *Controller) LeakCheck(reqJSON string) string {
	var req LeakRequest
	if err := json.Unmarshal([]byte(reqJSON), &req); err != nil {
		return errJSON("bad request: " + err.Error())
	}
	findings := leakcheck.InspectConfig(req.Config)
	var obs *leakcheck.Observer
	var expected []netip.Addr
	if req.Observer != nil {
		obs = &leakcheck.Observer{Zone: req.Observer.Zone, Endpoint: req.Observer.Endpoint}
		for _, s := range req.Observer.Expected {
			if a, err := netip.ParseAddr(strings.TrimSpace(s)); err == nil {
				expected = append(expected, a)
			}
		}
	}
	lookup := leakcheck.Lookup(func(ctx context.Context, name string) error {
		if req.Profile == nil {
			return errors.New("no resolver path selected")
		}
		p, err := req.Profile.profile()
		if err != nil {
			return err
		}
		r := c.resolve.Query(ctx, p, name, dnstools.TypeA, dnstools.Options{NoCache: true})
		if r.Error != "" {
			return errors.New(r.Error)
		}
		return nil
	})
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	findings = append(findings, leakcheck.ObserveDNS(ctx, obs, lookup, expected))
	v, msg := leakcheck.Overall(findings)
	return mustJSON(map[string]any{"findings": findings, "overall": v, "summary": msg})
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return errJSON(err.Error())
	}
	return string(b)
}

func errJSON(msg string) string { return mustJSON(map[string]string{"error": msg}) }
