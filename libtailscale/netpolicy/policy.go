// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package netpolicy is the single, deterministic precedence order for
// "where may this flow go". It is pure: callers supply what they know
// (destination, app uid, a *trusted* name) and get one Decision.
//
// Precedence, first match wins:
//
//  1. explicit deny (denied app, denied domain)            → block
//  2. reserved overlay names (.onion, .i2p, bad .onion)    → that backend, or block
//  3. private mesh destinations and mesh split-DNS names   → mesh (never proxied)
//  4. strict mode and the app could not be identified      → block
//  5. per-app rule                                         → that action
//  6. per-domain rule (only for a trusted name)            → that action
//  7. default
//
// A name is "trusted" only when it came from this app's own DNS
// answers (a virtual-address mapping or a logged answer for that very
// flow). A hostname is never inferred from a shared CDN address, and
// apps that use their own encrypted DNS simply have no trusted name:
// they get the per-app rule or the default.
package netpolicy

import (
	"net/netip"
	"strings"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

type Action string

const (
	Block  Action = "block"
	Mesh   Action = "mesh"   // the mesh engine
	Direct Action = "direct" // protected socket on the underlying network
	Proxy  Action = "proxy"  // the forced upstream proxy
	Tor    Action = "tor"
	I2P    Action = "i2p"
)

// Flow is what is known about one flow.
type Flow struct {
	Dst netip.Addr
	// Name and NameTrusted: the destination name, and whether it is
	// trustworthy metadata (see package comment).
	Name        string
	NameTrusted bool
	UID         int32
	UIDKnown    bool
}

// Policy is the whole table. Zero value = everything default-direct.
type Policy struct {
	DenyUIDs    map[int32]bool
	DenyDomains []string
	// TorReady / I2PReady say a matching backend is armed.
	TorReady, I2PReady bool
	// MeshZones are mesh split-DNS / private zones and search domains.
	MeshZones   []string
	AppRules    map[int32]Action
	DomainRules []DomainRule
	Default     Action
	// Strict blocks flows whose app could not be identified when any
	// per-app rule exists (fail closed, not "treat as default").
	Strict bool
}

type DomainRule struct {
	Pattern string // "example.com" or "*.example.com"
	Action  Action
}

// Decision explains itself so the UI can show why.
type Decision struct {
	Action Action
	Rule   string // short rule id: deny-app, deny-domain, reserved-onion, mesh, strict-unknown-uid, app, domain, default
	Why    string
}

func domainMatch(pat, name string) bool {
	pat = upstreamproxy.NormalizeName(pat)
	if rest, ok := strings.CutPrefix(pat, "*."); ok {
		return strings.HasSuffix(name, "."+rest)
	}
	return name == pat || strings.HasSuffix(name, "."+pat)
}

func isMeshAddr(a netip.Addr) bool {
	a = a.Unmap()
	return netip.MustParsePrefix("100.64.0.0/10").Contains(a) || netip.MustParsePrefix("fd7a:115c:a1e0::/48").Contains(a)
}

// Decide applies the precedence order.
func (p Policy) Decide(f Flow) Decision {
	name := upstreamproxy.NormalizeName(f.Name)
	// 1. explicit deny
	if f.UIDKnown && p.DenyUIDs[f.UID] {
		return Decision{Block, "deny-app", "this app is denied"}
	}
	if name != "" && f.NameTrusted {
		for _, d := range p.DenyDomains {
			if domainMatch(d, name) {
				return Decision{Block, "deny-domain", "domain " + d + " is denied"}
			}
		}
	}
	// 2. reserved overlay names
	if name != "" && f.NameTrusted || upstreamproxy.IsVirtualAddr(f.Dst) && name != "" {
		switch upstreamproxy.ClassifyName(name) {
		case upstreamproxy.NameOnionInvalid:
			return Decision{Block, "reserved-onion-invalid", "not a valid v3 .onion address"}
		case upstreamproxy.NameOnion:
			if p.TorReady {
				return Decision{Tor, "reserved-onion", ".onion names only go to Tor"}
			}
			return Decision{Block, "reserved-onion", ".onion needs a running Tor backend; refusing"}
		case upstreamproxy.NameI2P:
			if p.I2PReady {
				return Decision{I2P, "reserved-i2p", ".i2p names only go to I2P"}
			}
			return Decision{Block, "reserved-i2p", ".i2p needs a running I2P backend; refusing"}
		}
	}
	// 3. private mesh
	if isMeshAddr(f.Dst) {
		return Decision{Mesh, "mesh", "mesh address stays on the mesh"}
	}
	if name != "" && f.NameTrusted {
		for _, z := range p.MeshZones {
			if domainMatch(z, name) {
				return Decision{Mesh, "mesh-zone", "name is in a mesh private zone " + z}
			}
		}
	}
	// 4. strict + unknown app
	if !f.UIDKnown && p.Strict && len(p.AppRules) > 0 {
		return Decision{Block, "strict-unknown-uid", "app could not be identified and strict mode is on"}
	}
	// 5. per-app
	if f.UIDKnown {
		if a, ok := p.AppRules[f.UID]; ok {
			return Decision{a, "app", "per-app rule"}
		}
	}
	// 6. per-domain, trusted names only
	if name != "" && f.NameTrusted {
		for _, r := range p.DomainRules {
			if domainMatch(r.Pattern, name) {
				return Decision{r.Action, "domain", "domain rule " + r.Pattern}
			}
		}
	}
	// 7. default
	d := p.Default
	if d == "" {
		d = Direct
	}
	return Decision{d, "default", "no rule matched"}
}
