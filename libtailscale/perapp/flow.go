// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package perapp

import (
	"encoding/binary"
	"net/netip"
)

const (
	ProtoTCP uint8 = 6
	ProtoUDP uint8 = 17
)

// FlowKey names one TCP or UDP flow as the app sent it: Src is the
// app's address and port on the tunnel, Dst the remote side.
type FlowKey struct {
	Proto uint8
	Src   netip.AddrPort
	Dst   netip.AddrPort
}

// ParseFlow reads the flow key from a raw IPv4 or IPv6 packet. ok is
// false for anything that is not a first-fragment TCP or UDP packet
// (ping, other protocols, IPv4 fragments, IPv6 extension headers).
func ParseFlow(pkt []byte) (FlowKey, bool) {
	if len(pkt) < 1 {
		return FlowKey{}, false
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return FlowKey{}, false
		}
		ihl := int(pkt[0]&0x0f) * 4
		if ihl < 20 || len(pkt) < ihl+4 {
			return FlowKey{}, false
		}
		flags := binary.BigEndian.Uint16(pkt[6:8])
		if flags&0x1fff != 0 { // non-first fragment: no ports
			return FlowKey{}, false
		}
		proto := pkt[9]
		if proto != ProtoTCP && proto != ProtoUDP {
			return FlowKey{}, false
		}
		src := netip.AddrFrom4([4]byte(pkt[12:16]))
		dst := netip.AddrFrom4([4]byte(pkt[16:20]))
		sp := binary.BigEndian.Uint16(pkt[ihl : ihl+2])
		dp := binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
		return FlowKey{Proto: proto, Src: netip.AddrPortFrom(src, sp), Dst: netip.AddrPortFrom(dst, dp)}, true
	case 6:
		if len(pkt) < 44 {
			return FlowKey{}, false
		}
		proto := pkt[6]
		if proto != ProtoTCP && proto != ProtoUDP {
			return FlowKey{}, false
		}
		src := netip.AddrFrom16([16]byte(pkt[8:24])).Unmap()
		dst := netip.AddrFrom16([16]byte(pkt[24:40])).Unmap()
		sp := binary.BigEndian.Uint16(pkt[40:42])
		dp := binary.BigEndian.Uint16(pkt[42:44])
		return FlowKey{Proto: proto, Src: netip.AddrPortFrom(src, sp), Dst: netip.AddrPortFrom(dst, dp)}, true
	}
	return FlowKey{}, false
}

// DestAddr returns the destination address of a raw IP packet.
func DestAddr(pkt []byte) (netip.Addr, bool) {
	if len(pkt) < 1 {
		return netip.Addr{}, false
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return netip.Addr{}, false
		}
		return netip.AddrFrom4([4]byte(pkt[16:20])), true
	case 6:
		if len(pkt) < 40 {
			return netip.Addr{}, false
		}
		return netip.AddrFrom16([16]byte(pkt[24:40])).Unmap(), true
	}
	return netip.Addr{}, false
}
