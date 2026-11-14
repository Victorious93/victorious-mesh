// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"encoding/binary"
	"net/netip"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

// NameGate answers DNS queries for reserved overlay names locally so
// they never reach any DNS server:
//
//   - .onion (valid v3) → a virtual address when a Tor backend is armed;
//     otherwise REFUSED. Invalid/retired .onion → NXDOMAIN.
//   - .i2p / .b32.i2p → a virtual address when an I2P backend is armed;
//     otherwise REFUSED.
//
// It never sees, and never decides about, ordinary names, mesh names or
// private zones: those keep going to the mesh resolver unchanged.
type NameGate struct {
	Map *VMap
	// Tor and I2P report whether a matching backend is armed right now.
	// nil means "not armed".
	Tor func() bool
	I2P func() bool
	// TTL of synthetic answers, seconds. 0 means 30.
	TTL uint32
}

func (g *NameGate) armed(f func() bool) bool { return f != nil && f() }

// QueryName returns the first question's name and type.
func QueryName(q []byte) (name string, qtype dnsmessage.Type, ok bool) {
	var p dnsmessage.Parser
	if _, err := p.Start(q); err != nil {
		return "", 0, false
	}
	qs, err := p.Question()
	if err != nil {
		return "", 0, false
	}
	return NormalizeName(qs.Name.String()), qs.Type, true
}

// WantsQuery reports whether q is for a reserved overlay name this gate
// would answer itself.
func (g *NameGate) WantsQuery(q []byte) bool {
	name, _, ok := QueryName(q)
	if !ok {
		return false
	}
	switch ClassifyName(name) {
	case NameOnion, NameOnionInvalid, NameI2P:
		return true
	}
	return false
}

// AnswerQuery returns the local answer for a reserved-name query.
func (g *NameGate) AnswerQuery(q []byte) (resp []byte, handled bool) {
	var p dnsmessage.Parser
	h, err := p.Start(q)
	if err != nil {
		return nil, false
	}
	qs, err := p.Question()
	if err != nil {
		return nil, false
	}
	name := NormalizeName(qs.Name.String())
	class := ClassifyName(name)
	var ns string
	var armed bool
	switch class {
	case NameOnion:
		ns, armed = "tor", g.armed(g.Tor)
	case NameI2P:
		ns, armed = "i2p", g.armed(g.I2P)
	case NameOnionInvalid:
		return buildReply(h, qs, dnsmessage.RCodeNameError, nil), true
	default:
		return nil, false
	}
	if !armed || g.Map == nil {
		// No backend can resolve it: refuse. Nothing is forwarded.
		return buildReply(h, qs, dnsmessage.RCodeRefused, nil), true
	}
	if qs.Type != dnsmessage.TypeA && qs.Type != dnsmessage.TypeALL {
		return buildReply(h, qs, dnsmessage.RCodeSuccess, nil), true // NODATA
	}
	ip, err := g.Map.Assign(ns, name)
	if err != nil {
		return buildReply(h, qs, dnsmessage.RCodeServerFailure, nil), true
	}
	ttl := g.TTL
	if ttl == 0 {
		ttl = 30
	}
	ans := dnsmessage.Resource{
		Header: dnsmessage.ResourceHeader{Name: qs.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
		Body:   &dnsmessage.AResource{A: ip.As4()},
	}
	return buildReply(h, qs, dnsmessage.RCodeSuccess, []dnsmessage.Resource{ans}), true
}

func buildReply(req dnsmessage.Header, q dnsmessage.Question, rc dnsmessage.RCode, ans []dnsmessage.Resource) []byte {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: req.ID, Response: true, RecursionDesired: req.RecursionDesired,
		RecursionAvailable: true, RCode: rc,
	})
	b.EnableCompression()
	if err := b.StartQuestions(); err != nil {
		return nil
	}
	_ = b.Question(q)
	if len(ans) > 0 {
		_ = b.StartAnswers()
		for _, a := range ans {
			switch body := a.Body.(type) {
			case *dnsmessage.AResource:
				_ = b.AResource(a.Header, *body)
			}
		}
	}
	out, err := b.Finish()
	if err != nil {
		return nil
	}
	return out
}

// HandlePacket inspects one IP packet leaving the TUN. If it is a UDP
// DNS query (any destination, including the mesh resolver) for a
// reserved overlay name, it returns the complete reply IP packet to
// write back to the app, and the original must be dropped.
func (g *NameGate) HandlePacket(pkt []byte) (reply []byte, handled bool) {
	src, dst, sport, dport, payload, ok := udpParts(pkt)
	if !ok || dport != dnsPort {
		return nil, false
	}
	resp, ok := g.AnswerQuery(payload)
	if !ok || resp == nil {
		return nil, false
	}
	return buildUDPPacket(dst, src, dport, sport, resp), true
}

func udpParts(pkt []byte) (src, dst netip.Addr, sport, dport uint16, payload []byte, ok bool) {
	if len(pkt) < 1 {
		return
	}
	var ihl int
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 || pkt[9] != protoUDP {
			return
		}
		ihl = int(pkt[0]&0x0f) * 4
		// Fragments cannot be parsed reliably; leave them to the normal path.
		if binary.BigEndian.Uint16(pkt[6:8])&0x3fff != 0 {
			return
		}
		src, _ = netip.AddrFromSlice(pkt[12:16])
		dst, _ = netip.AddrFromSlice(pkt[16:20])
	case 6:
		if len(pkt) < 48 || pkt[6] != protoUDP {
			return
		}
		ihl = 40
		src, _ = netip.AddrFromSlice(pkt[8:24])
		dst, _ = netip.AddrFromSlice(pkt[24:40])
	default:
		return
	}
	if len(pkt) < ihl+8 {
		return
	}
	sport = binary.BigEndian.Uint16(pkt[ihl:])
	dport = binary.BigEndian.Uint16(pkt[ihl+2:])
	ulen := int(binary.BigEndian.Uint16(pkt[ihl+4:]))
	if ulen < 8 || ihl+ulen > len(pkt) {
		return
	}
	return src, dst, sport, dport, pkt[ihl+8 : ihl+ulen], true
}

// buildUDPPacket makes an IP+UDP packet from src to dst.
func buildUDPPacket(src, dst netip.Addr, sport, dport uint16, payload []byte) []byte {
	ulen := header.UDPMinimumSize + len(payload)
	if src.Is4() {
		pkt := make([]byte, header.IPv4MinimumSize+ulen)
		ip := header.IPv4(pkt)
		ip.Encode(&header.IPv4Fields{
			TotalLength: uint16(len(pkt)), TTL: 64, Protocol: uint8(header.UDPProtocolNumber),
			SrcAddr: tcpip.AddrFrom4(src.As4()), DstAddr: tcpip.AddrFrom4(dst.As4()),
		})
		ip.SetChecksum(^ip.CalculateChecksum())
		fillUDP(pkt[header.IPv4MinimumSize:], sport, dport, payload,
			header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom4(src.As4()), tcpip.AddrFrom4(dst.As4()), uint16(ulen)))
		return pkt
	}
	pkt := make([]byte, header.IPv6MinimumSize+ulen)
	ip := header.IPv6(pkt)
	ip.Encode(&header.IPv6Fields{
		PayloadLength: uint16(ulen), TransportProtocol: header.UDPProtocolNumber, HopLimit: 64,
		SrcAddr: tcpip.AddrFrom16(src.As16()), DstAddr: tcpip.AddrFrom16(dst.As16()),
	})
	fillUDP(pkt[header.IPv6MinimumSize:], sport, dport, payload,
		header.PseudoHeaderChecksum(header.UDPProtocolNumber, tcpip.AddrFrom16(src.As16()), tcpip.AddrFrom16(dst.As16()), uint16(ulen)))
	return pkt
}

func fillUDP(seg []byte, sport, dport uint16, payload []byte, pseudo uint16) {
	copy(seg[header.UDPMinimumSize:], payload)
	u := header.UDP(seg)
	u.Encode(&header.UDPFields{SrcPort: sport, DstPort: dport, Length: uint16(len(seg))})
	x := checksum.Checksum(payload, pseudo)
	u.SetChecksum(^u.CalculateChecksum(x))
}
