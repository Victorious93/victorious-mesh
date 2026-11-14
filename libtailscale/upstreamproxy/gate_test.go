// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"net/netip"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

func dnsQuery(t testing.TB, name string, typ dnsmessage.Type) []byte {
	t.Helper()
	n, err := dnsmessage.NewName(name + ".")
	if err != nil {
		t.Fatal(err)
	}
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 0x1234, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: n, Type: typ, Class: dnsmessage.ClassINET})
	out, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func parseReply(t testing.TB, b []byte) (dnsmessage.Header, []dnsmessage.Resource) {
	t.Helper()
	var p dnsmessage.Parser
	h, err := p.Start(b)
	if err != nil {
		t.Fatalf("reply: %v", err)
	}
	if err := p.SkipAllQuestions(); err != nil {
		t.Fatal(err)
	}
	ans, err := p.AllAnswers()
	if err != nil {
		t.Fatal(err)
	}
	return h, ans
}

func TestGateAnswersReservedNamesLocally(t *testing.T) {
	m := NewVMap(16, 0, nil)
	tor, i2p := true, true
	g := &NameGate{Map: m, Tor: func() bool { return tor }, I2P: func() bool { return i2p }}
	onion := makeOnion(3)

	h, ans := parseReply(t, mustAnswer(t, g, onion, dnsmessage.TypeA))
	if h.RCode != dnsmessage.RCodeSuccess || len(ans) != 1 || h.ID != 0x1234 || !h.Response {
		t.Fatalf("A: %+v %v", h, ans)
	}
	ip := netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)
	if !IsVirtualAddr(ip) {
		t.Fatalf("answer %v is not a virtual address", ip)
	}
	if ns, name, ok := m.Lookup(ip); !ok || ns != "tor" || name != onion {
		t.Fatalf("mapping %q %q %v", ns, name, ok)
	}
	// AAAA: NODATA, never a real or invented v6 address.
	if h, ans := parseReply(t, mustAnswer(t, g, onion, dnsmessage.TypeAAAA)); h.RCode != dnsmessage.RCodeSuccess || len(ans) != 0 {
		t.Fatalf("AAAA: %+v %v", h, ans)
	}
	// I2P lands in its own namespace.
	_, ans = parseReply(t, mustAnswer(t, g, "forum.i2p", dnsmessage.TypeA))
	ip2 := netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)
	if ns, _, _ := m.Lookup(ip2); ns != "i2p" {
		t.Fatalf("ns %q", ns)
	}
	// Invalid onion: NXDOMAIN, no mapping.
	before := m.Len()
	if h, _ := parseReply(t, mustAnswer(t, g, "expyuzz4wqqyqhjn.onion", dnsmessage.TypeA)); h.RCode != dnsmessage.RCodeNameError {
		t.Fatalf("invalid onion rcode %v", h.RCode)
	}
	if m.Len() != before {
		t.Fatal("invalid onion created a mapping")
	}
	// Backend not armed: REFUSED, no mapping, still handled (not forwarded).
	tor = false
	if h, ans := parseReply(t, mustAnswer(t, g, onion, dnsmessage.TypeA)); h.RCode != dnsmessage.RCodeRefused || len(ans) != 0 {
		t.Fatalf("unarmed: %+v", h)
	}
	// Ordinary, private and mesh names are NOT the gate's business.
	for _, n := range []string{"example.com", "peer.tailnet.ts.net", "nas.corp.internal", "printer.local"} {
		if resp, ok := g.AnswerQuery(dnsQuery(t, n, dnsmessage.TypeA)); ok || resp != nil {
			t.Fatalf("gate intercepted %q", n)
		}
		if g.WantsQuery(dnsQuery(t, n, dnsmessage.TypeA)) {
			t.Fatalf("WantsQuery(%q)", n)
		}
	}
	if !g.WantsQuery(dnsQuery(t, onion, dnsmessage.TypeA)) {
		t.Fatal("WantsQuery(onion)")
	}
}

func mustAnswer(t testing.TB, g *NameGate, name string, typ dnsmessage.Type) []byte {
	t.Helper()
	resp, ok := g.AnswerQuery(dnsQuery(t, name, typ))
	if !ok || resp == nil {
		t.Fatalf("%q %v not handled", name, typ)
	}
	return resp
}

// The packet path is what runs in the TUN: a query to the MESH resolver
// (100.100.100.100) for a reserved name must be answered locally with a
// well-formed, correctly checksummed reply from that same address.
func TestGatePacketPathAnswersAsTheResolver(t *testing.T) {
	g := &NameGate{Map: NewVMap(4, 0, nil), Tor: func() bool { return true }}
	for _, tc := range []struct{ src, dst netip.Addr }{
		{netip.MustParseAddr("100.64.0.9"), netip.MustParseAddr("100.100.100.100")},
		{netip.MustParseAddr("fd7a:115c:a1e0::9"), netip.MustParseAddr("fd7a:115c:a1e0::53")},
		{netip.MustParseAddr("10.0.0.2"), netip.MustParseAddr("8.8.8.8")},
	} {
		q := buildUDPPacket(tc.src, tc.dst, 40000, 53, dnsQuery(t, makeOnion(5), dnsmessage.TypeA))
		reply, ok := g.HandlePacket(q)
		if !ok {
			t.Fatalf("%v: not handled", tc.dst)
		}
		src, dst, sport, dport, payload, ok := udpParts(reply)
		if !ok || src != tc.dst || dst != tc.src || sport != 53 || dport != 40000 {
			t.Fatalf("reply addressing: %v %v %d %d", src, dst, sport, dport)
		}
		if tc.src.Is4() {
			if !header.IPv4(reply).IsChecksumValid() {
				t.Fatal("bad IPv4 checksum")
			}
			u := header.UDP(reply[20:])
			if !u.IsChecksumValid(tcpip.AddrFrom4(src.As4()), tcpip.AddrFrom4(dst.As4()), checksumOf(u.Payload(), 0)) {
				t.Fatal("bad UDP checksum")
			}
		}
		if tc.src.Is6() {
			u := header.UDP(reply[40:])
			if !u.IsChecksumValid(tcpip.AddrFrom16(src.As16()), tcpip.AddrFrom16(dst.As16()), checksumOf(u.Payload(), 0)) {
				t.Fatal("bad IPv6 UDP checksum")
			}
		}
		if h, ans := parseReply(t, payload); h.RCode != dnsmessage.RCodeSuccess || len(ans) != 1 {
			t.Fatalf("payload: %+v", h)
		}
	}
	// Ordinary queries and non-DNS UDP pass through untouched.
	if _, ok := g.HandlePacket(buildUDPPacket(netip.MustParseAddr("10.0.0.2"), netip.MustParseAddr("8.8.8.8"), 1, 53, dnsQuery(t, "example.com", dnsmessage.TypeA))); ok {
		t.Fatal("intercepted a public name")
	}
	if _, ok := g.HandlePacket(buildUDPPacket(netip.MustParseAddr("10.0.0.2"), netip.MustParseAddr("8.8.8.8"), 1, 5353, dnsQuery(t, makeOnion(5), dnsmessage.TypeA))); ok {
		t.Fatal("handled non-53 UDP")
	}
	if _, ok := g.HandlePacket([]byte{0x45, 0, 0}); ok {
		t.Fatal("handled garbage")
	}
}

func checksumOf(b []byte, initial uint16) uint16 { return checksum.Checksum(b, initial) }
