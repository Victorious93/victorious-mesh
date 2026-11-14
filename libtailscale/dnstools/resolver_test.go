// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package dnstools

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func udpProfile(addr string) Profile {
	return Profile{ID: "udp", Name: "udp", Transport: TransportUDP, Server: addr, Route: RouteDirect}
}

func rr(n string, qt dnsmessage.Type, body dnsmessage.ResourceBody) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: mustName(n + "."), Type: qt, Class: dnsmessage.ClassINET, TTL: 60}, Body: body}
}

func TestUDPLookupAndRendering(t *testing.T) {
	f := startUDP(t, func(n string, qt dnsmessage.Type) reply {
		switch qt {
		case dnsmessage.TypeMX:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.MXResource{Pref: 10, MX: mustName("mail.example.test.")})}}
		case dnsmessage.TypeTXT:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.TXTResource{TXT: []string{"v=spf1 -all", "second"}})}}
		case dnsmessage.TypeSRV:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.SRVResource{Priority: 1, Weight: 2, Port: 5060, Target: mustName("sip.example.test.")})}}
		case dnsmessage.TypeSOA:
			return reply{ans: []dnsmessage.Resource{soa(n, 300, 60)}}
		case dnsmessage.TypeNS:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.NSResource{NS: mustName("ns1.example.test.")})}}
		case dnsmessage.TypeCNAME:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.CNAMEResource{CNAME: mustName("real.example.test.")})}}
		case dnsmessage.TypeAAAA:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.AAAAResource{AAAA: netip.MustParseAddr("2001:db8::1").As16()})}}
		case dnsmessage.TypePTR:
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.PTRResource{PTR: mustName("host.example.test.")})}}
		case typeCAA:
			data := append([]byte{0, 5}, []byte("issue")...)
			data = append(data, []byte("letsencrypt.org")...)
			return reply{ans: []dnsmessage.Resource{rr(n, qt, &dnsmessage.UnknownResource{Type: qt, Data: data})}}
		}
		return reply{ans: []dnsmessage.Resource{a(n, "192.0.2.1", 60)}}
	})
	r := NewResolver(envFor(nil))
	p := udpProfile(f.udpAddr)
	want := map[QType]string{
		TypeA: "192.0.2.1", TypeAAAA: "2001:db8::1", TypeCNAME: "real.example.test", TypeMX: "10 mail.example.test",
		TypeNS: "ns1.example.test", TypeTXT: `"v=spf1 -all" "second"`, TypePTR: "host.example.test",
		TypeSRV: "1 2 5060 sip.example.test", TypeCAA: `0 issue "letsencrypt.org"`,
	}
	for qt, data := range want {
		res := r.Query(ctx5(t), p, "example.test", qt, Options{NoCache: true})
		if res.Error != "" || res.RCode != "NOERROR" || len(res.Answer) != 1 || res.Answer[0].Data != data || res.Answer[0].Type != string(qt) || res.Answer[0].TTL != 60 {
			t.Fatalf("%s: %+v", qt, res)
		}
		if res.Server != f.udpAddr || res.Transport != TransportUDP || res.Route != RouteDirect || res.Millis <= 0 || res.Cache != "bypass" {
			t.Fatalf("%s metadata: %+v", qt, res)
		}
	}
	soaRes := r.Query(ctx5(t), p, "example.test", TypeSOA, Options{})
	if !strings.Contains(soaRes.Answer[0].Data, "minimum=60") || !strings.Contains(soaRes.Answer[0].Data, "serial=7") {
		t.Fatalf("%+v", soaRes.Answer)
	}
	if !strings.Contains(soaRes.Text(), ";; status: NOERROR") || !strings.Contains(soaRes.JSON(), `"rcode": "NOERROR"`) {
		t.Fatal("export")
	}
	if got := ReverseName(netip.MustParseAddr("192.0.2.10")); got != "10.2.0.192.in-addr.arpa" {
		t.Fatal(got)
	}
	v6 := "b.a.9.8.7.6.5.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa"
	if got := ReverseName(netip.MustParseAddr("2001:db8::567:89ab")); got != v6 {
		t.Fatal(got)
	}
	if ip, ok := parseArpa(v6); !ok || ip != netip.MustParseAddr("2001:db8::567:89ab") {
		t.Fatalf("arpa v6 round trip: %v %v", ip, ok)
	}
	if _, err := ParseQType("ANY"); err == nil {
		t.Fatal("ANY accepted")
	}
}

func TestUDPTruncationRetriesOverTCP(t *testing.T) {
	udp := startUDP(t, func(n string, qt dnsmessage.Type) reply { return reply{tc: true} })
	_, port, _ := splitHP(udp.udpAddr)
	startTCPOn(t, "127.0.0.1:"+port, simple("192.0.2.9", 30))
	r := NewResolver(envFor(nil))
	res := r.Query(ctx5(t), udpProfile(udp.udpAddr), "big.example.test", TypeA, Options{NoCache: true})
	if res.Error != "" || !res.RetriedTCP || len(res.Answer) != 1 || res.Answer[0].Data != "192.0.2.9" {
		t.Fatalf("%+v", res)
	}
}

func TestDoTVerifiesCertificateAndNeverDowngrades(t *testing.T) {
	good, rogue := newCA(t), newCA(t)
	srv := startDoT(t, good.leaf(t, "dns.test", "127.0.0.1"), simple("192.0.2.5", 120))
	r := NewResolver(envFor(good))
	host, port, _ := splitHP(srv.tcpAddr)
	prof := Profile{ID: "dot", Name: "dot", Transport: TransportDoT, Server: "dns.test:" + port, ServerName: "dns.test", Bootstrap: []netip.Addr{netip.MustParseAddr(host)}, Route: RouteDirect}
	res := r.Query(ctx5(t), prof, "x.example.test", TypeA, Options{})
	if res.Error != "" || res.Answer[0].Data != "192.0.2.5" || srv.queries.Load() != 1 {
		t.Fatalf("%+v", res)
	}
	prof2 := prof
	prof2.ServerName = "other.test"
	if res := r.Query(ctx5(t), prof2, "y.example.test", TypeA, Options{}); res.Error == "" || !strings.Contains(res.Error, "not retried without TLS") {
		t.Fatalf("wrong name accepted: %+v", res)
	}
	rogueSrv := startDoT(t, rogue.leaf(t, "dns.test"), simple("203.0.113.1", 120))
	rh, rp, _ := splitHP(rogueSrv.tcpAddr)
	prof3 := prof
	prof3.Server = "dns.test:" + rp
	prof3.Bootstrap = []netip.Addr{netip.MustParseAddr(rh)}
	if res := r.Query(ctx5(t), prof3, "z.example.test", TypeA, Options{}); res.Error == "" {
		t.Fatalf("untrusted CA accepted: %+v", res)
	}
	if rogueSrv.queries.Load() != 0 {
		t.Fatal("query sent over an unverified channel")
	}
	plain := startTCP(t, simple("203.0.113.2", 5))
	ph, pp, _ := splitHP(plain.tcpAddr)
	prof4 := Profile{ID: "dot2", Name: "dot2", Transport: TransportDoT, Server: ph + ":" + pp, Route: RouteDirect}
	if res := r.Query(ctx5(t), prof4, "w.example.test", TypeA, Options{}); res.Error == "" || plain.queries.Load() != 0 {
		t.Fatalf("plaintext fallback: %+v queries=%d", res, plain.queries.Load())
	}
}

func TestHostnameResolverNeedsBootstrapAndNeverUsesOtherDNS(t *testing.T) {
	dials := 0
	env := Env{Dial: func(Route) DialFunc {
		return func(ctx context.Context, n, a string) (net.Conn, error) {
			dials++
			return nil, errors.New("must not dial")
		}
	}}
	r := NewResolver(env)
	for _, tr := range []Transport{TransportDoT, TransportDoH} {
		srv := "dns.example:853"
		if tr == TransportDoH {
			srv = "https://dns.example/dns-query"
		}
		res := r.Query(ctx5(t), Profile{ID: string(tr), Name: string(tr), Transport: tr, Server: srv, Route: RouteDirect}, "a.example.test", TypeA, Options{})
		if !strings.Contains(res.Error, "no bootstrap IPs") {
			t.Fatalf("%s: %+v", tr, res)
		}
	}
	if dials != 0 {
		t.Fatal("network used before bootstrap policy was satisfied")
	}
	var dialed string
	env2 := Env{
		Dial: func(Route) DialFunc {
			return func(ctx context.Context, n, a string) (net.Conn, error) { dialed = a; return nil, errors.New("stop") }
		},
		RouteResolvesNames: func(r Route) bool { return r == RouteProxy },
	}
	NewResolver(env2).Query(ctx5(t), Profile{ID: "p", Name: "p", Transport: TransportDoT, Server: "dns.example", Route: RouteProxy}, "a.test", TypeA, Options{})
	if dialed != "dns.example:853" {
		t.Fatalf("dialed %q", dialed)
	}
}

func TestDoHVerifiedAndStrict(t *testing.T) {
	good, rogue := newCA(t), newCA(t)
	srv, addr := startDoH(t, good.leaf(t, "doh.test", "127.0.0.1"), simple("192.0.2.7", 90))
	host, port, _ := splitHP(addr)
	r := NewResolver(envFor(good))
	prof := Profile{ID: "doh", Name: "doh", Transport: TransportDoH, Server: "https://doh.test:" + port + "/dns-query", Bootstrap: []netip.Addr{netip.MustParseAddr(host)}, Route: RouteDirect}
	res := r.Query(ctx5(t), prof, "q.example.test", TypeA, Options{})
	if res.Error != "" || res.Answer[0].Data != "192.0.2.7" || srv.queries.Load() != 1 {
		t.Fatalf("%+v", res)
	}
	srv2, addr2 := startDoH(t, rogue.leaf(t, "doh.test"), simple("203.0.113.9", 90))
	h2, p2, _ := splitHP(addr2)
	prof2 := prof
	prof2.Server = "https://doh.test:" + p2 + "/dns-query"
	prof2.Bootstrap = []netip.Addr{netip.MustParseAddr(h2)}
	if res := r.Query(ctx5(t), prof2, "q2.example.test", TypeA, Options{}); res.Error == "" || srv2.queries.Load() != 0 {
		t.Fatalf("rogue DoH accepted: %+v", res)
	}
	prof3 := prof
	prof3.Server = "http://doh.test/dns-query"
	if res := r.Query(ctx5(t), prof3, "q3.example.test", TypeA, Options{}); !strings.Contains(res.Error, "https://") {
		t.Fatalf("%+v", res)
	}
}

func TestRouteUnavailableDoesNotReroute(t *testing.T) {
	f := startUDP(t, simple("192.0.2.1", 60))
	r := NewResolver(envFor(nil))
	p := udpProfile(f.udpAddr)
	for _, rt := range []Route{RouteProxy, RouteTor} {
		p.Route = rt
		res := r.Query(ctx5(t), p, "a.example.test", TypeA, Options{})
		if !strings.Contains(res.Error, "not falling back") {
			t.Fatalf("%s: %+v", rt, res)
		}
	}
	if f.queries.Load() != 0 {
		t.Fatal("query leaked to the direct route")
	}
}

func TestNoReservedOrPrivateNameLeaksToPublicResolvers(t *testing.T) {
	udp := startUDP(t, simple("192.0.2.1", 60))
	r := NewResolver(envFor(nil))
	pub := udpProfile(udp.udpAddr)
	private := pub
	private.ID, private.Private = "priv", true
	onion := "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
	leaks := []struct {
		name string
		qt   QType
	}{
		{onion, TypeA}, {"EXPYUZZ4WQQYQHJN.onion.", TypeA}, {"forum.i2p", TypeA}, {"x.b32.i2p", TypeTXT},
		{"printer.local", TypeA}, {"nas.home.arpa", TypeA}, {"x.localhost", TypeAAAA},
		{"7.0.0.10.in-addr.arpa", TypePTR}, {"3.2.64.100.in-addr.arpa", TypePTR}, {"1.0.168.192.in-addr.arpa", TypePTR},
		{ReverseName(netip.MustParseAddr("fd7a:115c:a1e0::5")), TypePTR},
	}
	for _, l := range leaks {
		if res := r.Query(ctx5(t), pub, l.name, l.qt, Options{}); res.Error == "" {
			t.Fatalf("%s %s was sent to a public resolver", l.name, l.qt)
		}
	}
	if udp.queries.Load() != 0 {
		t.Fatalf("resolver saw %v", udp.seenNames())
	}
	for _, n := range []string{onion, "forum.i2p"} {
		if res := r.Query(ctx5(t), private, n, TypeA, Options{}); !strings.Contains(res.Error, "reserved name") {
			t.Fatalf("%s: %+v", n, res)
		}
	}
	if res := r.Query(ctx5(t), private, "printer.local", TypeA, Options{}); res.Error != "" {
		t.Fatalf("%+v", res)
	}
	if res := r.Query(ctx5(t), pub, "34.216.184.93.in-addr.arpa", TypePTR, Options{}); res.Error != "" {
		t.Fatalf("%+v", res)
	}
}

func TestCacheTTLNegativeAndIsolation(t *testing.T) {
	clk := &clock{t: time.Unix(10000, 0)}
	env := envFor(nil)
	env.Now = clk.now
	r := NewResolver(env)
	f1 := startUDP(t, simple("192.0.2.1", 100))
	f2 := startUDP(t, simple("198.51.100.2", 100))
	p1, p2 := udpProfile(f1.udpAddr), udpProfile(f2.udpAddr)
	p2.ID = "other"
	q := func(p Profile, name string, qt QType) *Result { return r.Query(ctx5(t), p, name, qt, Options{}) }

	if res := q(p1, "a.example.test", TypeA); res.Cache != "miss" {
		t.Fatalf("%+v", res)
	}
	clk.add(30 * time.Second)
	res := q(p1, "a.example.test", TypeA)
	if res.Cache != "hit" || res.Answer[0].TTL != 70 || res.CacheTTL != 70 || f1.queries.Load() != 1 {
		t.Fatalf("hit: %+v q=%d", res, f1.queries.Load())
	}
	if res := q(p2, "a.example.test", TypeA); res.Cache != "miss" || res.Answer[0].Data != "198.51.100.2" {
		t.Fatalf("cache leaked across profiles: %+v", res)
	}
	clk.add(71 * time.Second)
	if res := q(p1, "a.example.test", TypeA); res.Cache != "miss" || f1.queries.Load() != 2 {
		t.Fatalf("expired entry served: %+v", res)
	}
	if res := q(p1, "a.example.test", TypeMX); res.Cache != "miss" || len(res.Answer) != 0 {
		t.Fatalf("%+v", res)
	}
	clk.add(59 * time.Second)
	if res := q(p1, "a.example.test", TypeMX); res.Cache != "negative-hit" {
		t.Fatalf("%+v", res)
	}
	clk.add(2 * time.Second)
	if res := q(p1, "a.example.test", TypeMX); res.Cache != "miss" {
		t.Fatalf("negative entry outlived SOA minimum: %+v", res)
	}
	q(p1, "b.example.test", TypeA)
	if r.Cache().Len() == 0 {
		t.Fatal("nothing cached")
	}
	r.Cache().Bump()
	if res := q(p1, "b.example.test", TypeA); res.Cache != "miss" {
		t.Fatalf("stale generation served: %+v", res)
	}
	r.Cache().Clear()
	if r.Cache().Len() != 0 {
		t.Fatal("clear")
	}
	q(p1, "c.example.test", TypeA)
	q(p2, "c.example.test", TypeA)
	if n := r.Cache().InvalidateProfile("udp"); n != 1 || r.Cache().Len() != 1 {
		t.Fatalf("invalidate profile: %d len=%d", n, r.Cache().Len())
	}
}

func TestCacheDoesNotStoreFailuresAndIsBounded(t *testing.T) {
	servfail := startUDP(t, func(string, dnsmessage.Type) reply { return reply{rcode: dnsmessage.RCodeServerFailure} })
	r := NewResolver(envFor(nil))
	p := udpProfile(servfail.udpAddr)
	r.Query(ctx5(t), p, "a.example.test", TypeA, Options{})
	r.Query(ctx5(t), p, "a.example.test", TypeA, Options{})
	if servfail.queries.Load() != 2 || r.Cache().Len() != 0 {
		t.Fatal("SERVFAIL was cached")
	}
	c := NewCache(8, nil)
	for i := 0; i < 50; i++ {
		c.Put(cacheKey{profile: "p", name: string(rune('a'+i%26)) + string(rune('a'+i/26)), qt: TypeA}, &Message{RCode: "NOERROR", Answer: []RR{{Type: "A"}}, MinTTL: 100, EDE: -1})
	}
	if c.Len() > 8 {
		t.Fatalf("cache exceeded bound: %d", c.Len())
	}
}

func TestLocalHostsAllowBlockNeedNoNetwork(t *testing.T) {
	f := startUDP(t, simple("192.0.2.1", 60))
	r := NewResolver(envFor(nil))
	r.SetLocalPolicy(LocalPolicy{
		Hosts: map[string][]netip.Addr{"nas.test": {netip.MustParseAddr("192.168.1.10"), netip.MustParseAddr("fd00::10")}},
		Block: []string{"*.ads.test", "tracker.test"},
		Allow: []string{"ok.ads.test"},
	})
	p := udpProfile(f.udpAddr)
	if res := r.Query(ctx5(t), p, "nas.test", TypeA, Options{}); res.Cache != "local" || res.Answer[0].Data != "192.168.1.10" {
		t.Fatalf("%+v", res)
	}
	if res := r.Query(ctx5(t), p, "nas.test", TypeAAAA, Options{}); res.Answer[0].Data != "fd00::10" {
		t.Fatalf("%+v", res)
	}
	if res := r.Query(ctx5(t), p, "nas.test", TypeMX, Options{}); res.RCode != "NOERROR" || len(res.Answer) != 0 || res.Cache != "local" {
		t.Fatalf("mapped names are authoritative: %+v", res)
	}
	for _, n := range []string{"x.ads.test", "tracker.test"} {
		if res := r.Query(ctx5(t), p, n, TypeA, Options{}); res.Cache != "blocked" || res.RCode != "NXDOMAIN" {
			t.Fatalf("%s: %+v", n, res)
		}
	}
	if res := r.Query(ctx5(t), p, "ok.ads.test", TypeA, Options{}); res.Cache != "miss" || res.Error != "" {
		t.Fatalf("allow rule: %+v", res)
	}
	if res := r.Query(ctx5(t), p, "ads.test", TypeA, Options{}); res.Cache == "blocked" {
		t.Fatal("*.ads.test must not match the apex")
	}
	if got := f.seenNames(); len(got) != 2 {
		t.Fatalf("network used for local answers: %v", got)
	}
}

func TestDNSSECReportingDistinguishesClaimFromValidation(t *testing.T) {
	good := newCA(t)
	ad := func(n string, qt dnsmessage.Type) reply {
		return reply{ad: true, ans: []dnsmessage.Resource{a(n, "192.0.2.1", 60)}}
	}
	adUDP := startUDP(t, ad)
	dot := startDoT(t, good.leaf(t, "dns.test", "127.0.0.1"), ad)
	plain := startUDP(t, simple("192.0.2.1", 60))
	r := NewResolver(envFor(good))
	h, port, _ := splitHP(dot.tcpAddr)
	dotP := Profile{ID: "dot", Name: "dot", Transport: TransportDoT, Server: "dns.test:" + port, Bootstrap: []netip.Addr{netip.MustParseAddr(h)}, Route: RouteDirect}

	d := r.Query(ctx5(t), udpProfile(adUDP.udpAddr), "s.example.test", TypeA, Options{DNSSEC: true}).DNSSEC
	if !d.ResolverClaimsAD || d.ValidatedLocally || d.PathAuthenticated || !strings.Contains(d.Summary, "could be forged") {
		t.Fatalf("%+v", d)
	}
	d = r.Query(ctx5(t), dotP, "s.example.test", TypeA, Options{DNSSEC: true}).DNSSEC
	if !d.ResolverClaimsAD || d.ValidatedLocally || !d.PathAuthenticated || !strings.Contains(d.Summary, "does not validate DNSSEC itself") {
		t.Fatalf("%+v", d)
	}
	plainP := udpProfile(plain.udpAddr)
	plainP.ID = "plain"
	d = r.Query(ctx5(t), plainP, "s.example.test", TypeA, Options{DNSSEC: true}).DNSSEC
	if d.ResolverClaimsAD || !strings.Contains(d.Summary, "No DNSSEC evidence") {
		t.Fatalf("%+v", d)
	}
	d = r.Query(ctx5(t), plainP, "t.example.test", TypeA, Options{}).DNSSEC
	if d.Requested || !strings.Contains(d.Summary, "not requested") {
		t.Fatalf("%+v", d)
	}
}

func TestCompareHistoryAndSplitPick(t *testing.T) {
	f1 := startUDP(t, simple("192.0.2.1", 60))
	f2 := startUDP(t, simple("192.0.2.1", 60))
	f3 := startUDP(t, simple("203.0.113.3", 60))
	r := NewResolver(envFor(nil))
	mk := func(id string, f *fakeDNS) Profile { p := udpProfile(f.udpAddr); p.ID, p.Name = id, id; return p }
	c := r.Compare(ctx5(t), []Profile{mk("a", f1), mk("b", f2)}, "x.example.test", TypeA, Options{})
	if !c.Agree || c.Fastest == "" || !strings.Contains(c.Summary, "agree") {
		t.Fatalf("%+v", c)
	}
	c = r.Compare(ctx5(t), []Profile{mk("a", f1), mk("c", f3)}, "x.example.test", TypeA, Options{})
	if c.Agree || !strings.Contains(c.Summary, "disagree") {
		t.Fatalf("%+v", c)
	}
	dead := udpProfile("127.0.0.1:1")
	dead.Route, dead.ID, dead.Name = RouteTor, "dead", "dead"
	c = r.Compare(ctx5(t), []Profile{mk("a", f1), dead}, "x.example.test", TypeA, Options{})
	if !strings.Contains(c.Summary, "1 of 2") {
		t.Fatalf("%+v", c)
	}
	if r.Cache().Len() != 0 {
		t.Fatal("compare touched the cache")
	}
	lat := r.Latency(ctx5(t), mk("a", f1), "x.example.test", TypeA, 3)
	if lat.OK != 3 || lat.MinMs <= 0 || lat.MaxMs < lat.MinMs {
		t.Fatalf("%+v", lat)
	}

	h := NewHistory()
	h.Add(&Result{Name: "secret.example"})
	if len(h.Items()) != 0 || h.Enabled() {
		t.Fatal("history must be off by default and record nothing")
	}
	h.SetEnabled(true)
	for i := 0; i < 150; i++ {
		h.Add(&Result{Name: "n"})
	}
	if len(h.Items()) != 100 {
		t.Fatalf("not bounded: %d", len(h.Items()))
	}
	h.SetEnabled(false)
	if len(h.Items()) != 0 {
		t.Fatal("disabling must clear")
	}

	def := Profile{ID: "def"}
	ps := []Profile{{ID: "corp", Domains: []string{"corp.example", "ts.net"}}, {ID: "sub", Domains: []string{"dev.corp.example"}}}
	for name, want := range map[string]string{"x.corp.example": "corp", "a.dev.corp.example": "sub", "peer.ts.net": "corp", "corp.example": "corp", "evilcorp.example": "def", "example.com": "def"} {
		if got := PickProfile(ps, def, name).ID; got != want {
			t.Errorf("%s -> %s want %s", name, got, want)
		}
	}
}
