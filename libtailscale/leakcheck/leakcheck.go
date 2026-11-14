// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package leakcheck separates three different kinds of claim:
//
//   - config:   what the settings say should happen (inspection only);
//   - observed: something was actually seen on the wire or by a service
//     that this app does not control the answer of... or does, in a
//     fixture (see Evidence docs per finding);
//   - verified: an observation that directly answers the question
//     (for DNS: an observation service saw, or did not see, the query).
//
// A missing measurement is "inconclusive", never "pass". A public-IP
// lookup alone says nothing about DNS.
package leakcheck

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"
)

type Verdict string

const (
	Pass         Verdict = "pass"
	Warn         Verdict = "warn"
	Fail         Verdict = "fail"
	Inconclusive Verdict = "inconclusive"
)

type Evidence string

const (
	EvidenceConfig   Evidence = "configuration"
	EvidenceObserved Evidence = "observed"
	EvidenceVerified Evidence = "verified"
)

type Finding struct {
	ID       string   `json:"id"`
	Title    string   `json:"title"`
	Verdict  Verdict  `json:"verdict"`
	Evidence Evidence `json:"evidence"`
	Detail   string   `json:"detail"`
}

// ConfigInputs is what inspection reads. Nothing here is measured.
type ConfigInputs struct {
	VPNActive            bool
	KillSwitch           bool
	ForcedProxy          bool
	ProxyFailClosed      bool
	DNSMode              string // "mesh", "system", "custom", "forced-proxy", "doh", "dot"
	EncryptedDNS         bool
	TorRequired          bool
	TorBackendUp         bool
	I2PRequired          bool
	I2PBackendUp         bool
	PerNetworkDNSIgnored bool // per-network rules exist but are paused/ignored
	DirectApps           int
}

// InspectConfig reports what is configured. It never returns a
// verified pass: configuration cannot prove behaviour.
func InspectConfig(in ConfigInputs) []Finding {
	var f []Finding
	add := func(id, title string, v Verdict, d string) {
		f = append(f, Finding{ID: id, Title: title, Verdict: v, Evidence: EvidenceConfig, Detail: d})
	}
	if in.VPNActive {
		add("vpn", "VPN", Pass, "The VPN is configured as up. This is not a test of what apps actually send.")
	} else {
		add("vpn", "VPN", Fail, "The VPN is down; traffic uses Wi-Fi or cellular directly.")
	}
	if in.KillSwitch {
		add("killswitch", "Kill switch", Pass, "Configured on.")
	} else {
		add("killswitch", "Kill switch", Warn, "Off: a tunnel drop can send traffic directly.")
	}
	if in.ForcedProxy && !in.ProxyFailClosed {
		add("proxy-failclosed", "Proxy fail-closed", Warn, "The device-wide proxy is set to fall back to a direct connection when it is down.")
	}
	if in.PerNetworkDNSIgnored {
		add("pndns", "Per-network DNS", Warn, "Per-Wi-Fi/cellular DNS rules exist but are NOT being applied right now (forced proxy sends DNS through the proxy).")
	}
	if in.TorRequired && !in.TorBackendUp {
		add("tor", "Tor", Warn, "Tor is required but the backend is not reachable; protected traffic is blocked, not leaked.")
	}
	if in.I2PRequired && !in.I2PBackendUp {
		add("i2p", "I2P", Warn, "I2P is required but the router is not reachable; protected traffic is blocked, not leaked.")
	}
	if in.DirectApps > 0 {
		add("direct", "Direct apps", Warn, fmt.Sprintf("%d app(s) are set to bypass the tunnel on purpose.", in.DirectApps))
	}
	add("dns-config", "DNS configuration", Inconclusive, "Configured resolver mode: "+in.DNSMode+". Whether queries really go there is only known after the observed DNS test.")
	add("scope", "Scope", Warn, "Cannot test other apps' own encrypted DNS (DoH/DoT), WebRTC/STUN, IPv6 outside the tunnel, or sockets opened before the VPN.")
	return f
}

// Observer is an observation service the user controls (or a test
// fixture): it is the authoritative name server for Zone, and records
// which source addresses asked for which label.
//
// Protocol (documented in the manual): a lookup of <token>.<Zone> from
// the device makes the service log the resolver's source IPs; a later
// GET <Endpoint>?token=<token> returns {"resolvers":["203.0.113.5"]}.
type Observer struct {
	Zone     string // e.g. "leak.example.org"
	Endpoint string // https URL of the results endpoint
	HTTP     *http.Client
}

// Lookup triggers one lookup of name through the path under test and
// reports whether it was attempted. Its own answer is irrelevant.
type Lookup func(ctx context.Context, name string) error

// ObserveDNS runs the real test: it asks the path under test to resolve
// a unique name and then asks the observation service who asked. The
// expected set are the resolver addresses the configuration says should
// be seen (for example the proxy's exit or the DoH provider's egress).
//
// Verdicts: Pass only if the service saw the query, and every observed
// resolver is in expected; Fail if one is outside it; Inconclusive if
// the service saw nothing (cannot tell a drop from a miss), if no
// observer is configured, or if expected is unknown.
func ObserveDNS(ctx context.Context, obs *Observer, lookup Lookup, expected []netip.Addr) Finding {
	fd := Finding{ID: "dns-observed", Title: "DNS path (observed)", Evidence: EvidenceVerified}
	if obs == nil || obs.Zone == "" || obs.Endpoint == "" {
		fd.Verdict, fd.Evidence = Inconclusive, EvidenceConfig
		fd.Detail = "No observation service is configured, so DNS leakage could not be measured. A public-IP check does not test DNS."
		return fd
	}
	if u, err := url.Parse(obs.Endpoint); err != nil || u.Scheme != "https" || u.Host == "" {
		fd.Verdict, fd.Evidence = Inconclusive, EvidenceConfig
		fd.Detail = "The observation endpoint must be an https:// URL."
		return fd
	}
	var tok [8]byte
	_, _ = rand.Read(tok[:])
	token := hex.EncodeToString(tok[:])
	name := token + "." + strings.TrimPrefix(obs.Zone, ".")
	if err := lookup(ctx, name); err != nil {
		// The lookup itself may legitimately fail (NXDOMAIN) while still
		// reaching the authoritative server; only the observation counts.
		fd.Detail = "lookup returned: " + err.Error() + ". "
	}
	seen, err := obs.fetch(ctx, token)
	if err != nil {
		fd.Verdict, fd.Evidence = Inconclusive, EvidenceObserved
		fd.Detail += "Could not read the observation service: " + err.Error()
		return fd
	}
	if len(seen) == 0 {
		fd.Verdict, fd.Evidence = Inconclusive, EvidenceObserved
		fd.Detail += "The observation service saw no query for the test name. The lookup may have been blocked, cached, or never sent; this does not prove there was no leak."
		return fd
	}
	want := map[netip.Addr]bool{}
	for _, a := range expected {
		want[a.Unmap()] = true
	}
	var outside []string
	for _, a := range seen {
		if !want[a.Unmap()] {
			outside = append(outside, a.String())
		}
	}
	sort.Strings(outside)
	switch {
	case len(want) == 0:
		fd.Verdict = Inconclusive
		fd.Detail += "The service saw the query from " + joinAddrs(seen) + ", but no expected resolver set is known to compare against."
	case len(outside) > 0:
		fd.Verdict = Fail
		fd.Detail += "The query reached the observation service from " + strings.Join(outside, ", ") + ", which is not an expected resolver path."
	default:
		fd.Verdict = Pass
		fd.Detail += "The service saw the test query only from expected resolver address(es): " + joinAddrs(seen) + "."
	}
	return fd
}

func joinAddrs(a []netip.Addr) string {
	s := make([]string, len(a))
	for i, x := range a {
		s[i] = x.String()
	}
	return strings.Join(s, ", ")
}

func (o *Observer) fetch(ctx context.Context, token string) ([]netip.Addr, error) {
	cl := o.HTTP
	if cl == nil {
		cl = &http.Client{Timeout: 10 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, o.Endpoint+"?token="+url.QueryEscape(token), nil)
	if err != nil {
		return nil, err
	}
	// Give the service a moment to log the query it just received.
	var last error
	for i := 0; i < 3; i++ {
		resp, err := cl.Do(req.Clone(ctx))
		if err != nil {
			return nil, err
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("service answered %s", resp.Status)
		}
		var out struct {
			Resolvers []string `json:"resolvers"`
		}
		if err := json.Unmarshal(body, &out); err != nil {
			return nil, fmt.Errorf("unreadable reply: %w", err)
		}
		var addrs []netip.Addr
		for _, r := range out.Resolvers {
			a, err := netip.ParseAddr(r)
			if err != nil {
				last = fmt.Errorf("bad address %q", r)
				continue
			}
			addrs = append(addrs, a)
		}
		if len(addrs) > 0 || i == 2 {
			return addrs, last
		}
		select {
		case <-time.After(300 * time.Millisecond):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, last
}

// Overall is the headline. It is "verified" only when an observed DNS
// test passed; otherwise the best it can say is that nothing was
// measured.
func Overall(fs []Finding) (Verdict, string) {
	var observed *Finding
	worst := Pass
	for i := range fs {
		f := &fs[i]
		if f.ID == "dns-observed" {
			observed = f
		}
		if f.Verdict == Fail {
			worst = Fail
		} else if f.Verdict == Warn && worst != Fail {
			worst = Warn
		}
	}
	switch {
	case worst == Fail:
		return Fail, "A check failed."
	case observed == nil || observed.Verdict == Inconclusive:
		return Inconclusive, "Configuration was inspected, but DNS leakage was not measured. This is not a clean bill of health."
	case worst == Warn:
		return Warn, "The observed DNS test passed; some configuration warnings remain."
	}
	return Pass, "The observed DNS test passed. Other apps' own encrypted DNS and WebRTC are still out of scope."
}

// ------------------------------------------------------- test fixture

// FixtureServer is a controlled observation service for tests and for
// self-hosting: a UDP name server for Zone that records the source
// addresses of queries. Run it on a host you control, delegate Zone to
// it, and serve Resolvers(token) as the JSON results endpoint.
type FixtureServer struct {
	Zone string
	pc   net.PacketConn
	mu   sync.Mutex
	data map[string]map[string]bool
}

// NewFixtureServer listens on a loopback UDP port.
func NewFixtureServer(zone string) (*FixtureServer, error) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s := &FixtureServer{Zone: zone, pc: pc, data: map[string]map[string]bool{}}
	go s.loop()
	return s, nil
}

func (s *FixtureServer) Addr() string { return s.pc.LocalAddr().String() }
func (s *FixtureServer) Close()       { s.pc.Close() }

func (s *FixtureServer) loop() {
	buf := make([]byte, 1500)
	for {
		n, addr, err := s.pc.ReadFrom(buf)
		if err != nil {
			return
		}
		name := parseQName(buf[:n])
		if rest, ok := strings.CutSuffix(name, "."+s.Zone); ok {
			tok := rest[strings.LastIndex(rest, ".")+1:]
			host, _, _ := net.SplitHostPort(addr.String())
			s.mu.Lock()
			if s.data[tok] == nil {
				s.data[tok] = map[string]bool{}
			}
			s.data[tok][host] = true
			s.mu.Unlock()
		}
		resp := append([]byte(nil), buf[:n]...) // minimal NXDOMAIN
		if len(resp) > 3 {
			resp[2] |= 0x80
			resp[3] = (resp[3] &^ 0x0f) | 3
		}
		s.pc.WriteTo(resp, addr)
	}
}

// Resolvers returns the sources seen for a token.
func (s *FixtureServer) Resolvers(tok string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []string
	for k := range s.data[tok] {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func parseQName(b []byte) string {
	if len(b) < 13 {
		return ""
	}
	var labels []string
	i := 12
	for i < len(b) && b[i] != 0 {
		l := int(b[i])
		if i+1+l > len(b) {
			return ""
		}
		labels = append(labels, strings.ToLower(string(b[i+1:i+1+l])))
		i += 1 + l
	}
	return strings.Join(labels, ".")
}
