// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"encoding/binary"
	"net/netip"

	"tailscale.com/net/tsaddr"
)

const (
	protoTCP = 6
	protoUDP = 17
)

// DestAddr returns the IPv4/IPv6 destination of an IP packet.
func DestAddr(pkt []byte) (netip.Addr, bool) {
	if len(pkt) < 1 {
		return netip.Addr{}, false
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return netip.Addr{}, false
		}
		var a [4]byte
		copy(a[:], pkt[16:20])
		return netip.AddrFrom4(a), true
	case 6:
		if len(pkt) < 40 {
			return netip.Addr{}, false
		}
		var a [16]byte
		copy(a[:], pkt[24:40])
		return netip.AddrFrom16(a), true
	default:
		return netip.Addr{}, false
	}
}

func Transport(pkt []byte) (proto uint8, srcPort, dstPort uint16, ok bool) {
	if len(pkt) < 1 {
		return 0, 0, 0, false
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return 0, 0, 0, false
		}
		ihl := int(pkt[0]&0x0f) * 4
		proto = pkt[9]
		if len(pkt) < ihl+4 || (proto != protoTCP && proto != protoUDP) {
			return proto, 0, 0, proto == protoTCP || proto == protoUDP
		}
		srcPort = binary.BigEndian.Uint16(pkt[ihl:])
		dstPort = binary.BigEndian.Uint16(pkt[ihl+2:])
		return proto, srcPort, dstPort, true
	case 6:
		if len(pkt) < 40 {
			return 0, 0, 0, false
		}
		proto = pkt[6]
		if len(pkt) < 44 || (proto != protoTCP && proto != protoUDP) {
			return proto, 0, 0, proto == protoTCP || proto == protoUDP
		}
		srcPort = binary.BigEndian.Uint16(pkt[40:])
		dstPort = binary.BigEndian.Uint16(pkt[42:])
		return proto, srcPort, dstPort, true
	default:
		return 0, 0, 0, false
	}
}

func IsMeshDest(pkt []byte) bool {
	addr, ok := DestAddr(pkt)
	return ok && tsaddr.IsTailscaleIP(addr)
}

const dnsPort = 53

// IsDNS reports whether pkt is TCP or UDP destined to port 53.
func IsDNS(pkt []byte) bool {
	proto, _, dst, ok := Transport(pkt)
	return ok && dst == dnsPort && (proto == protoUDP || proto == protoTCP)
}

// IsMeshDNS is a DNS packet whose destination is a mesh / MagicDNS address.
// Those stay on the mesh and are not rewritten through the upstream.
func IsMeshDNS(pkt []byte) bool {
	return IsDNS(pkt) && IsMeshDest(pkt)
}

func IsIPv6(pkt []byte) bool {
	return len(pkt) > 0 && pkt[0]>>4 == 6
}

func DefaultRoutes() []netip.Prefix {
	return []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/0"),
		netip.MustParsePrefix("::/0"),
	}
}
