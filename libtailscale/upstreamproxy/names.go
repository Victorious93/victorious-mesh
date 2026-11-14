// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"crypto/sha3"
	"encoding/base32"
	"net/netip"
	"strings"
)

// NameClass says which resolver may ever see a name.
type NameClass int

const (
	// NamePublic is an ordinary DNS name.
	NamePublic NameClass = iota
	// NameOnion is a syntactically valid v3 onion service name. Only
	// Tor may resolve it.
	NameOnion
	// NameOnionInvalid ends in .onion but is not a valid v3 address
	// (v2 addresses are retired and rejected). Never resolvable, never
	// sent to DNS.
	NameOnionInvalid
	// NameI2P is *.i2p (including *.b32.i2p). Only an I2P router may
	// resolve it.
	NameI2P
	// NameLocal is .local / .localhost / .home.arpa / .internal /
	// single-label names: link-local or private, never public DNS.
	NameLocal
)

func (c NameClass) String() string {
	switch c {
	case NamePublic:
		return "public"
	case NameOnion:
		return "onion"
	case NameOnionInvalid:
		return "onion-invalid"
	case NameI2P:
		return "i2p"
	case NameLocal:
		return "local"
	}
	return "unknown"
}

// Reserved reports classes that must never reach a public resolver.
func (c NameClass) Reserved() bool { return c != NamePublic }

// NormalizeName lowercases and strips one trailing dot and spaces.
func NormalizeName(n string) string {
	return strings.TrimSuffix(strings.ToLower(strings.TrimSpace(n)), ".")
}

// ClassifyName decides a name's class from its text alone.
func ClassifyName(name string) NameClass {
	n := NormalizeName(name)
	switch {
	case n == "onion" || strings.HasSuffix(n, ".onion"):
		if ValidOnionV3(n) {
			return NameOnion
		}
		return NameOnionInvalid
	case n == "i2p" || strings.HasSuffix(n, ".i2p"):
		return NameI2P
	case n == "local" || strings.HasSuffix(n, ".local"),
		n == "localhost" || strings.HasSuffix(n, ".localhost"),
		strings.HasSuffix(n, ".home.arpa"), strings.HasSuffix(n, ".internal"),
		strings.HasSuffix(n, ".lan"), strings.HasSuffix(n, ".intranet"), strings.HasSuffix(n, ".corp"):
		return NameLocal
	}
	return NamePublic
}

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// ValidOnionV3 checks the rend-spec-v3 address: the label immediately
// before ".onion" is base32(pubkey[32] || checksum[2] || version=3) with
// checksum = SHA3-256(".onion checksum" || pubkey || version)[:2]. Any
// number of subdomain labels may precede it.
func ValidOnionV3(name string) bool {
	n := NormalizeName(name)
	if !strings.HasSuffix(n, ".onion") {
		return false
	}
	labels := strings.Split(strings.TrimSuffix(n, ".onion"), ".")
	label := labels[len(labels)-1]
	if len(label) != 56 {
		return false
	}
	raw, err := b32.DecodeString(strings.ToUpper(label))
	if err != nil || len(raw) != 35 || raw[34] != 3 {
		return false
	}
	h := sha3.New256()
	h.Write([]byte(".onion checksum"))
	h.Write(raw[:32])
	h.Write([]byte{3})
	sum := h.Sum(nil)
	return sum[0] == raw[32] && sum[1] == raw[33]
}

// NameResolver turns a virtual address handed out for a reserved name
// back into that name.
type NameResolver interface {
	LookupVirtual(ip netip.Addr) (name string, ns string, ok bool)
}

// I2PName reports whether n is a name an I2P router can resolve.
func I2PName(n string) bool { return ClassifyName(n) == NameI2P }

// VirtualPrefix is the synthetic IPv4 range used for reserved names in
// transparent TUN mode (RFC 2544 benchmark space, never routed on the
// public internet). Only addresses inside it are ever mapped.
var VirtualPrefix = netip.MustParsePrefix("198.18.0.0/15")

// IsVirtualAddr reports whether ip lies in the synthetic range.
func IsVirtualAddr(ip netip.Addr) bool { return VirtualPrefix.Contains(ip.Unmap()) }
