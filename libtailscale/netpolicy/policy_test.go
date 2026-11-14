// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package netpolicy

import (
	"net/netip"
	"testing"
)

const goodOnion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"

func ip(s string) netip.Addr { return netip.MustParseAddr(s) }

func TestPrecedence(t *testing.T) {
	p := Policy{
		DenyUIDs:    map[int32]bool{10: true},
		DenyDomains: []string{"*.blocked.test"},
		TorReady:    true,
		MeshZones:   []string{"corp.example"},
		AppRules:    map[int32]Action{20: Proxy, 30: Direct, 11: Proxy},
		DomainRules: []DomainRule{{"video.test", Proxy}, {"*.mail.test", Tor}},
		Default:     Direct,
		Strict:      true,
	}
	vip := ip("198.18.0.4")
	cases := []struct {
		name string
		f    Flow
		want Action
		rule string
	}{
		{"denied app beats everything incl. mesh", Flow{Dst: ip("100.64.0.1"), UID: 10, UIDKnown: true}, Block, "deny-app"},
		{"denied app beats onion", Flow{Dst: vip, Name: goodOnion, NameTrusted: true, UID: 10, UIDKnown: true}, Block, "deny-app"},
		{"denied domain beats app rule", Flow{Dst: ip("93.184.216.34"), Name: "a.blocked.test", NameTrusted: true, UID: 20, UIDKnown: true}, Block, "deny-domain"},
		{"onion beats per-app direct", Flow{Dst: vip, Name: goodOnion, NameTrusted: true, UID: 30, UIDKnown: true}, Tor, "reserved-onion"},
		{"invalid onion blocked", Flow{Dst: vip, Name: "expyuzz4wqqyqhjn.onion", NameTrusted: true}, Block, "reserved-onion-invalid"},
		{"i2p without backend blocked", Flow{Dst: vip, Name: "forum.i2p", NameTrusted: true, UID: 30, UIDKnown: true}, Block, "reserved-i2p"},
		{"mesh address stays mesh despite proxy rule", Flow{Dst: ip("100.101.102.103"), UID: 20, UIDKnown: true}, Mesh, "mesh"},
		{"mesh v6", Flow{Dst: ip("fd7a:115c:a1e0::9")}, Mesh, "mesh"},
		{"mesh split zone name stays mesh", Flow{Dst: ip("93.184.216.34"), Name: "git.corp.example", NameTrusted: true, UID: 20, UIDKnown: true}, Mesh, "mesh-zone"},
		{"unknown uid under strict blocks", Flow{Dst: ip("93.184.216.34")}, Block, "strict-unknown-uid"},
		{"app rule", Flow{Dst: ip("93.184.216.34"), UID: 20, UIDKnown: true}, Proxy, "app"},
		{"app rule beats domain rule", Flow{Dst: ip("93.184.216.34"), Name: "video.test", NameTrusted: true, UID: 30, UIDKnown: true}, Direct, "app"},
		{"domain rule when app has none", Flow{Dst: ip("93.184.216.34"), Name: "video.test", NameTrusted: true, UID: 99, UIDKnown: true}, Proxy, "domain"},
		{"wildcard domain rule", Flow{Dst: ip("93.184.216.34"), Name: "x.mail.test", NameTrusted: true, UID: 99, UIDKnown: true}, Tor, "domain"},
		{"UNTRUSTED name is ignored (CDN guess)", Flow{Dst: ip("93.184.216.34"), Name: "video.test", NameTrusted: false, UID: 99, UIDKnown: true}, Direct, "default"},
		{"no name, no rule", Flow{Dst: ip("93.184.216.34"), UID: 99, UIDKnown: true}, Direct, "default"},
	}
	for _, c := range cases {
		got := p.Decide(c.f)
		if got.Action != c.want || got.Rule != c.rule || got.Why == "" {
			t.Errorf("%s: got %+v, want %s/%s", c.name, got, c.want, c.rule)
		}
	}
	// An untrusted deny is not applied (cannot be proven), but the trusted one is.
	if got := p.Decide(Flow{Dst: ip("93.184.216.34"), Name: "a.blocked.test", UID: 99, UIDKnown: true}); got.Action == Block {
		t.Errorf("untrusted name triggered a deny: %+v", got)
	}
}

func TestNonStrictUnknownUIDFallsToDefault(t *testing.T) {
	p := Policy{AppRules: map[int32]Action{20: Proxy}, Default: Direct}
	if got := p.Decide(Flow{Dst: ip("93.184.216.34")}); got.Action != Direct || got.Rule != "default" {
		t.Fatalf("%+v", got)
	}
}

func TestOnionWithoutTorBlocksNotLeaks(t *testing.T) {
	p := Policy{Default: Direct}
	for _, n := range []string{goodOnion, "forum.i2p"} {
		got := p.Decide(Flow{Dst: ip("198.18.0.9"), Name: n, NameTrusted: true})
		if got.Action != Block {
			t.Fatalf("%s: %+v", n, got)
		}
	}
	// Tor ready for onion, I2P not.
	p.TorReady = true
	if got := p.Decide(Flow{Dst: ip("198.18.0.9"), Name: "forum.i2p", NameTrusted: true}); got.Action != Block {
		t.Fatalf("%+v", got)
	}
}
