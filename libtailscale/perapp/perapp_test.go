// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package perapp

import (
	"encoding/binary"
	"net/netip"
	"testing"
	"time"
)

func v4(proto uint8, src, dst string, sp, dp uint16) []byte {
	p := make([]byte, 28)
	p[0] = 0x45
	p[9] = proto
	s := netip.MustParseAddr(src).As4()
	d := netip.MustParseAddr(dst).As4()
	copy(p[12:16], s[:])
	copy(p[16:20], d[:])
	binary.BigEndian.PutUint16(p[20:22], sp)
	binary.BigEndian.PutUint16(p[22:24], dp)
	return p
}

func v6(proto uint8, src, dst string, sp, dp uint16) []byte {
	p := make([]byte, 48)
	p[0] = 0x60
	p[6] = proto
	s := netip.MustParseAddr(src).As16()
	d := netip.MustParseAddr(dst).As16()
	copy(p[8:24], s[:])
	copy(p[24:40], d[:])
	binary.BigEndian.PutUint16(p[40:42], sp)
	binary.BigEndian.PutUint16(p[42:44], dp)
	return p
}

const policyJSON = `{"enabled":true,"exitNodeActive":false,"rules":[
 {"uid":10100,"action":"proxy","proxy":{"kind":"socks5","host":"198.51.100.7","port":1080}},
 {"uid":10101,"action":"block"},
 {"uid":10102,"action":"engine"},
 {"uid":10103,"action":"proxy","proxy":{"kind":"http","host":"198.51.100.8","port":8080,"failClosed":false}}
]}`

func mustPolicy(t *testing.T, raw string) Policy {
	t.Helper()
	p, err := Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestParsePolicy(t *testing.T) {
	p := mustPolicy(t, policyJSON)
	if !p.Active() || !p.NeedsCapture() || !p.Strict() {
		t.Fatalf("flags: %+v", p)
	}
	if r := p.Rules[10100]; r.Action != ActionProxy || !r.Proxy.FailClosed || r.Proxy.Port != 1080 {
		t.Fatalf("proxy rule: %+v", r)
	}
	if r := p.Rules[10103]; r.Proxy.FailClosed {
		t.Fatalf("explicit failClosed=false lost: %+v", r)
	}
}

func TestParseEmptyIsOff(t *testing.T) {
	p := mustPolicy(t, "")
	if p.Active() || p.NeedsCapture() || p.Strict() {
		t.Fatal("empty policy must be off")
	}
	p = mustPolicy(t, `{"enabled":false,"rules":[{"uid":1,"action":"block"}]}`)
	if p.Active() || p.NeedsCapture() {
		t.Fatal("disabled policy must be off")
	}
}

func TestParseBadRulesFailClosed(t *testing.T) {
	p := mustPolicy(t, `{"enabled":true,"rules":[
	 {"uid":1,"action":"proxy"},
	 {"uid":2,"action":"proxy","proxy":{"kind":"socks5","host":"","port":1080}},
	 {"uid":3,"action":"proxy","proxy":{"kind":"gopher","host":"x","port":1080}},
	 {"uid":4,"action":"proxy","proxy":{"kind":"http","host":"x","port":80}},
	 {"uid":5,"action":"teleport"},
	 {"uid":6,"action":"proxy","proxy":{"kind":"http","host":"x","port":8080,"username":"u"}}
	]}`)
	for uid := int32(1); uid <= 6; uid++ {
		if p.Rules[uid].Action != ActionBlock {
			t.Errorf("uid %d: got %v, want block", uid, p.Rules[uid].Action)
		}
	}
	if _, err := Parse("{nope"); err == nil {
		t.Fatal("bad JSON must error")
	}
}

func TestSharedUIDConflictBlocks(t *testing.T) {
	p := mustPolicy(t, `{"enabled":true,"rules":[{"uid":7,"action":"engine"},{"uid":7,"action":"default"}]}`)
	if p.Rules[7].Action != ActionBlock {
		t.Fatalf("conflict: %v", p.Rules[7].Action)
	}
	p = mustPolicy(t, `{"enabled":true,"rules":[{"uid":7,"action":"engine"},{"uid":7,"action":"engine"}]}`)
	if p.Rules[7].Action != ActionEngine {
		t.Fatalf("same: %v", p.Rules[7].Action)
	}
}

func TestParseFlow(t *testing.T) {
	k, ok := ParseFlow(v4(ProtoTCP, "10.0.0.2", "93.184.216.34", 40000, 443))
	if !ok || k.Proto != ProtoTCP || k.Src.Port() != 40000 || k.Dst.String() != "93.184.216.34:443" {
		t.Fatalf("v4: %v %v", k, ok)
	}
	k, ok = ParseFlow(v6(ProtoUDP, "fd00::2", "2001:db8::1", 5000, 53))
	if !ok || k.Proto != ProtoUDP || k.Dst.Port() != 53 {
		t.Fatalf("v6: %v %v", k, ok)
	}
	if _, ok := ParseFlow(v4(1, "10.0.0.2", "1.1.1.1", 0, 0)); ok {
		t.Fatal("ICMP must not parse")
	}
	frag := v4(ProtoTCP, "10.0.0.2", "1.1.1.1", 1, 2)
	binary.BigEndian.PutUint16(frag[6:8], 10) // fragment offset 10
	if _, ok := ParseFlow(frag); ok {
		t.Fatal("non-first fragment must not parse")
	}
	if _, ok := ParseFlow(v6(0, "fd00::2", "2001:db8::1", 1, 2)); ok {
		t.Fatal("IPv6 ext header must not parse")
	}
	if _, ok := ParseFlow([]byte{0x45, 0}); ok {
		t.Fatal("short packet must not parse")
	}
}

func owners(m map[uint16]int32) OwnerFunc {
	return func(k FlowKey) (int32, bool) {
		u, ok := m[k.Src.Port()]
		return u, ok
	}
}

func TestClassifyRoutes(t *testing.T) {
	p := mustPolicy(t, policyJSON)
	c := NewClassifier(owners(map[uint16]int32{1: 10100, 2: 10101, 3: 10102, 4: 10103, 5: 10999}))
	cases := []struct {
		port   uint16
		forced bool
		want   RouteKind
	}{
		{1, false, RouteProxy},
		{2, false, RouteDrop},
		{3, false, RouteEngine},
		{4, false, RouteProxy},
		{5, false, RouteDirect}, // no rule, no exit node, tunnel capturing
		{5, true, RouteForced},  // no rule, forced proxy on
		{3, true, RouteEngine},  // picked the device exit node: skips forced proxy
		{1, true, RouteProxy},
	}
	for i, tc := range cases {
		c.Reset()
		got := c.Classify(v4(ProtoTCP, "100.64.0.1", "93.184.216.34", tc.port, 443), p, 1, tc.forced)
		if got.Kind != tc.want {
			t.Errorf("case %d port %d forced=%v: got %v want %v", i, tc.port, tc.forced, got.Kind, tc.want)
		}
	}
}

func TestDefaultUsesExitNodeWhenActive(t *testing.T) {
	p := mustPolicy(t, `{"enabled":true,"exitNodeActive":true,"rules":[{"uid":1,"action":"block"}]}`)
	c := NewClassifier(owners(map[uint16]int32{9: 2}))
	if got := c.Classify(v4(ProtoTCP, "100.64.0.1", "1.1.1.1", 9, 443), p, 1, false); got.Kind != RouteEngine {
		t.Fatalf("got %v", got.Kind)
	}
}

func TestMeshAndSubnetStayOnEngine(t *testing.T) {
	p := mustPolicy(t, policyJSON)
	c := NewClassifier(owners(map[uint16]int32{1: 10101})) // a blocked app
	c.SetMeshRoutes([]netip.Prefix{
		netip.MustParsePrefix("100.64.0.0/10"),
		netip.MustParsePrefix("192.168.50.0/24"),
		netip.MustParsePrefix("0.0.0.0/0"),
	})
	for _, dst := range []string{"100.100.100.100", "100.70.1.2", "192.168.50.9"} {
		if got := c.Classify(v4(ProtoUDP, "100.64.0.1", dst, 1, 53), p, 1, false); got.Kind != RouteEngine {
			t.Errorf("%s: got %v", dst, got.Kind)
		}
	}
	if got := c.Classify(v6(ProtoTCP, "fd7a:115c:a1e0::1", "fd7a:115c:a1e0::2", 1, 22), p, 1, false); got.Kind != RouteEngine {
		t.Errorf("mesh v6: got %v", got.Kind)
	}
	// 0/0 from an exit node is not "mesh": a blocked app stays blocked.
	if got := c.Classify(v4(ProtoTCP, "100.64.0.1", "8.8.8.8", 1, 443), p, 1, false); got.Kind != RouteDrop {
		t.Errorf("internet: got %v", got.Kind)
	}
}

func TestUnattributableTraffic(t *testing.T) {
	ping := v4(1, "100.64.0.1", "1.1.1.1", 0, 0)
	strict := mustPolicy(t, policyJSON)
	c := NewClassifier(nil)
	if got := c.Classify(ping, strict, 1, false); got.Kind != RouteDrop {
		t.Errorf("strict ping: %v", got.Kind)
	}
	loose := mustPolicy(t, `{"enabled":true,"exitNodeActive":true,"rules":[{"uid":1,"action":"engine"}]}`)
	if got := c.Classify(ping, loose, 1, false); got.Kind != RouteEngine {
		t.Errorf("loose ping: %v", got.Kind)
	}
	if got := c.Classify(ping, Policy{}, 1, true); got.Kind != RouteForced {
		t.Errorf("forced only ping: %v", got.Kind)
	}
	openProxy := mustPolicy(t, `{"enabled":true,"rules":[{"uid":1,"action":"proxy","proxy":{"kind":"http","host":"x","port":8080,"failClosed":false}}]}`)
	if got := c.Classify(ping, openProxy, 1, false); got.Kind != RouteDrop {
		t.Errorf("direct relay cannot carry ping: %v", got.Kind)
	}
}

func TestUnknownOwner(t *testing.T) {
	strict := mustPolicy(t, policyJSON)
	calls := 0
	c := NewClassifier(func(FlowKey) (int32, bool) { calls++; return -1, false })
	pkt := v4(ProtoTCP, "100.64.0.1", "1.1.1.1", 7, 443)
	if got := c.Classify(pkt, strict, 1, false); got.Kind != RouteDrop {
		t.Fatalf("strict unknown: %v", got.Kind)
	}
	c.Classify(pkt, strict, 1, false)
	if calls != 2 || c.Len() != 0 {
		t.Fatalf("strict unknown must not be cached: calls=%d len=%d", calls, c.Len())
	}
	if c.UnknownOwners() != 2 {
		t.Fatalf("unknown count %d", c.UnknownOwners())
	}
	loose := mustPolicy(t, `{"enabled":true,"exitNodeActive":true,"rules":[{"uid":1,"action":"engine"}]}`)
	if got := c.Classify(pkt, loose, 2, false); got.Kind != RouteEngine {
		t.Fatalf("loose unknown: %v", got.Kind)
	}
}

func TestCacheAndGeneration(t *testing.T) {
	p := mustPolicy(t, policyJSON)
	calls := 0
	now := time.Unix(1000, 0)
	c := NewClassifier(func(FlowKey) (int32, bool) { calls++; return 10100, true })
	c.Now = func() time.Time { return now }
	pkt := v4(ProtoTCP, "100.64.0.1", "1.1.1.1", 7, 443)
	for i := 0; i < 5; i++ {
		c.Classify(pkt, p, 1, false)
	}
	if calls != 1 {
		t.Fatalf("owner asked %d times, want 1", calls)
	}
	c.Classify(pkt, p, 2, false) // policy changed
	if calls != 2 {
		t.Fatalf("generation change must re-ask, calls=%d", calls)
	}
	now = now.Add(tcpIdle + time.Second)
	c.Classify(pkt, p, 2, false)
	if calls != 3 {
		t.Fatalf("idle flow must re-ask, calls=%d", calls)
	}
	udp := v4(ProtoUDP, "100.64.0.1", "1.1.1.1", 8, 443)
	c.Classify(udp, p, 2, false)
	now = now.Add(udpIdle + time.Second)
	c.Classify(udp, p, 2, false)
	if calls != 5 {
		t.Fatalf("udp idle must re-ask, calls=%d", calls)
	}
}

func TestCacheBounded(t *testing.T) {
	p := mustPolicy(t, policyJSON)
	c := NewClassifier(func(FlowKey) (int32, bool) { return 10100, true })
	for i := 0; i < maxFlows+10; i++ {
		c.Classify(v4(ProtoUDP, "100.64.0.1", "1.1.1.1", uint16(i%60000)+1, uint16(i/60000)+1), p, 1, false)
	}
	if c.Len() > maxFlows {
		t.Fatalf("cache grew to %d", c.Len())
	}
}

func TestSetGetClear(t *testing.T) {
	Set(mustPolicy(t, policyJSON))
	if !Get().Active() {
		t.Fatal("set")
	}
	Clear()
	if Get().Active() {
		t.Fatal("clear")
	}
}
