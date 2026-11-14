// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package dnstools is the resolver-profile and lookup-tool layer behind
// Settings → DNS. Pure Go; the Android glue injects the dialers that
// decide which route each resolver profile uses.
package dnstools

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"strings"

	"golang.org/x/net/dns/dnsmessage"
)

// QType is a record type the tools can ask for.
type QType string

const (
	TypeA     QType = "A"
	TypeAAAA  QType = "AAAA"
	TypeCNAME QType = "CNAME"
	TypeMX    QType = "MX"
	TypeNS    QType = "NS"
	TypeTXT   QType = "TXT"
	TypeSOA   QType = "SOA"
	TypePTR   QType = "PTR"
	TypeSRV   QType = "SRV"
	TypeCAA   QType = "CAA"
)

// typeCAA is RFC 8659's type number; dnsmessage has no constant for it.
const typeCAA dnsmessage.Type = 257

var typeNums = map[QType]dnsmessage.Type{
	TypeA: dnsmessage.TypeA, TypeAAAA: dnsmessage.TypeAAAA, TypeCNAME: dnsmessage.TypeCNAME,
	TypeMX: dnsmessage.TypeMX, TypeNS: dnsmessage.TypeNS, TypeTXT: dnsmessage.TypeTXT,
	TypeSOA: dnsmessage.TypeSOA, TypePTR: dnsmessage.TypePTR, TypeSRV: dnsmessage.TypeSRV, TypeCAA: typeCAA,
}

// ParseQType accepts the ten supported types, case-insensitively.
func ParseQType(s string) (QType, error) {
	q := QType(strings.ToUpper(strings.TrimSpace(s)))
	if _, ok := typeNums[q]; !ok {
		return "", fmt.Errorf("unsupported record type %q (A, AAAA, CNAME, MX, NS, TXT, SOA, PTR, SRV, CAA)", s)
	}
	return q, nil
}

// ReverseName is the in-addr.arpa / ip6.arpa name for an address.
func ReverseName(ip netip.Addr) string {
	ip = ip.Unmap()
	if ip.Is4() {
		b := ip.As4()
		return fmt.Sprintf("%d.%d.%d.%d.in-addr.arpa", b[3], b[2], b[1], b[0])
	}
	b := ip.As16()
	var sb strings.Builder
	for i := 15; i >= 0; i-- {
		fmt.Fprintf(&sb, "%x.%x.", b[i]&0xf, b[i]>>4)
	}
	return sb.String() + "ip6.arpa"
}

// RR is one rendered record.
type RR struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Class string `json:"class"`
	TTL   uint32 `json:"ttl"`
	Data  string `json:"data"`
}

// Flags are the header bits a user can act on.
type Flags struct {
	AA bool `json:"aa"`
	TC bool `json:"tc"`
	RD bool `json:"rd"`
	RA bool `json:"ra"`
	AD bool `json:"ad"`
	CD bool `json:"cd"`
}

// Message is a parsed DNS response.
type Message struct {
	ID         uint16
	RCode      string
	Flags      Flags
	Answer     []RR
	Authority  []RR
	Additional []RR
	// RRSIGs counts RRSIG records anywhere in the response.
	RRSIGs int
	// MinTTL is the smallest TTL among answers (0 if none).
	MinTTL uint32
	// SOAMin is the negative-caching TTL from an authority SOA
	// (RFC 2308: min(SOA TTL, SOA MINIMUM)); 0 if there is none.
	SOAMin uint32
	// EDE is the RFC 8914 extended error code, -1 if absent.
	EDE int
}

// BuildQuery makes a recursive query with EDNS0 (1232-byte UDP size).
// dnssecOK sets the DO bit so a validating resolver returns RRSIGs.
func BuildQuery(id uint16, name string, t QType, dnssecOK bool) ([]byte, error) {
	num, ok := typeNums[t]
	if !ok {
		return nil, fmt.Errorf("unsupported type %q", t)
	}
	n, err := dnsmessage.NewName(strings.TrimSuffix(name, ".") + ".")
	if err != nil {
		return nil, fmt.Errorf("bad name %q: %w", name, err)
	}
	b := dnsmessage.NewBuilder(make([]byte, 0, 512), dnsmessage.Header{ID: id, RecursionDesired: true})
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(dnsmessage.Question{Name: n, Type: num, Class: dnsmessage.ClassINET}); err != nil {
		return nil, err
	}
	if err := b.StartAdditionals(); err != nil {
		return nil, err
	}
	var rh dnsmessage.ResourceHeader
	if err := rh.SetEDNS0(1232, dnsmessage.RCodeSuccess, dnssecOK); err != nil {
		return nil, err
	}
	if err := b.OPTResource(rh, dnsmessage.OPTResource{}); err != nil {
		return nil, err
	}
	return b.Finish()
}

// QueryID returns the ID of a wire message.
func QueryID(b []byte) (uint16, error) {
	if len(b) < 2 {
		return 0, errors.New("short dns message")
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

// ParseResponse renders a response for display and caching.
func ParseResponse(b []byte) (*Message, error) {
	var p dnsmessage.Parser
	h, err := p.Start(b)
	if err != nil {
		return nil, fmt.Errorf("malformed DNS response: %w", err)
	}
	m := &Message{
		ID: h.ID, RCode: rcodeName(h.RCode), EDE: -1,
		Flags: Flags{AA: h.Authoritative, TC: h.Truncated, RD: h.RecursionDesired, RA: h.RecursionAvailable, AD: h.AuthenticData, CD: h.CheckingDisabled},
	}
	if err := p.SkipAllQuestions(); err != nil {
		return nil, fmt.Errorf("malformed question section: %w", err)
	}
	read := func(sect string, next func() (dnsmessage.Resource, error), out *[]RR) error {
		for {
			r, err := next()
			if errors.Is(err, dnsmessage.ErrSectionDone) {
				return nil
			}
			if err != nil {
				return fmt.Errorf("malformed %s section: %w", sect, err)
			}
			rr := render(r)
			if r.Header.Type == dnsmessage.TypeOPT {
				if code := edeOf(r); code >= 0 {
					m.EDE = code
				}
				continue
			}
			if rr.Type == "RRSIG" {
				m.RRSIGs++
			}
			if sect == "answer" && (m.MinTTL == 0 || r.Header.TTL < m.MinTTL) && rr.Type != "RRSIG" {
				m.MinTTL = r.Header.TTL
			}
			if sect == "authority" && r.Header.Type == dnsmessage.TypeSOA {
				if soa, ok := r.Body.(*dnsmessage.SOAResource); ok {
					m.SOAMin = min(r.Header.TTL, soa.MinTTL)
				}
			}
			*out = append(*out, rr)
		}
	}
	if err := read("answer", p.Answer, &m.Answer); err != nil {
		return nil, err
	}
	if err := read("authority", p.Authority, &m.Authority); err != nil {
		return nil, err
	}
	if err := read("additional", p.Additional, &m.Additional); err != nil {
		return nil, err
	}
	return m, nil
}

func rcodeName(r dnsmessage.RCode) string {
	switch r {
	case dnsmessage.RCodeSuccess:
		return "NOERROR"
	case dnsmessage.RCodeFormatError:
		return "FORMERR"
	case dnsmessage.RCodeServerFailure:
		return "SERVFAIL"
	case dnsmessage.RCodeNameError:
		return "NXDOMAIN"
	case dnsmessage.RCodeNotImplemented:
		return "NOTIMP"
	case dnsmessage.RCodeRefused:
		return "REFUSED"
	}
	return fmt.Sprintf("RCODE%d", uint16(r))
}

func edeOf(r dnsmessage.Resource) int {
	opt, ok := r.Body.(*dnsmessage.OPTResource)
	if !ok {
		return -1
	}
	for _, o := range opt.Options {
		if o.Code == 15 && len(o.Data) >= 2 {
			return int(o.Data[0])<<8 | int(o.Data[1])
		}
	}
	return -1
}

func typeName(t dnsmessage.Type) string {
	switch t {
	case typeCAA:
		return "CAA"
	case 46:
		return "RRSIG"
	case 48:
		return "DNSKEY"
	case 43:
		return "DS"
	case 47:
		return "NSEC"
	case 50:
		return "NSEC3"
	}
	s := t.String()
	return strings.TrimPrefix(s, "Type")
}

func render(r dnsmessage.Resource) RR {
	rr := RR{
		Name: strings.TrimSuffix(r.Header.Name.String(), "."), Type: typeName(r.Header.Type),
		Class: strings.TrimPrefix(r.Header.Class.String(), "Class"), TTL: r.Header.TTL,
	}
	switch b := r.Body.(type) {
	case *dnsmessage.AResource:
		rr.Data = netip.AddrFrom4(b.A).String()
	case *dnsmessage.AAAAResource:
		rr.Data = netip.AddrFrom16(b.AAAA).String()
	case *dnsmessage.CNAMEResource:
		rr.Data = strings.TrimSuffix(b.CNAME.String(), ".")
	case *dnsmessage.NSResource:
		rr.Data = strings.TrimSuffix(b.NS.String(), ".")
	case *dnsmessage.PTRResource:
		rr.Data = strings.TrimSuffix(b.PTR.String(), ".")
	case *dnsmessage.MXResource:
		rr.Data = fmt.Sprintf("%d %s", b.Pref, strings.TrimSuffix(b.MX.String(), "."))
	case *dnsmessage.TXTResource:
		rr.Data = `"` + strings.Join(b.TXT, `" "`) + `"`
	case *dnsmessage.SOAResource:
		rr.Data = fmt.Sprintf("%s %s serial=%d refresh=%d retry=%d expire=%d minimum=%d",
			strings.TrimSuffix(b.NS.String(), "."), strings.TrimSuffix(b.MBox.String(), "."), b.Serial, b.Refresh, b.Retry, b.Expire, b.MinTTL)
	case *dnsmessage.SRVResource:
		rr.Data = fmt.Sprintf("%d %d %d %s", b.Priority, b.Weight, b.Port, strings.TrimSuffix(b.Target.String(), "."))
	case *dnsmessage.UnknownResource:
		if r.Header.Type == typeCAA {
			rr.Data = renderCAA(b.Data)
		} else if r.Header.Type == 46 && len(b.Data) >= 18 {
			rr.Data = fmt.Sprintf("covers=%s (%d bytes)", typeName(dnsmessage.Type(uint16(b.Data[0])<<8|uint16(b.Data[1]))), len(b.Data))
		} else if len(b.Data) <= 64 {
			rr.Data = hex.EncodeToString(b.Data)
		} else {
			rr.Data = fmt.Sprintf("%d bytes (%s…)", len(b.Data), base64.StdEncoding.EncodeToString(b.Data[:12]))
		}
	case *dnsmessage.OPTResource:
		rr.Data = "EDNS0"
	}
	return rr
}

func renderCAA(d []byte) string {
	if len(d) < 2 || int(d[1])+2 > len(d) {
		return hex.EncodeToString(d)
	}
	tl := int(d[1])
	return fmt.Sprintf("%d %s %q", d[0], string(d[2:2+tl]), string(d[2+tl:]))
}
