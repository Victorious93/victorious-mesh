// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"
)

// DNS is always fail-closed in forced mode: if the proxy is down the
// lookup is blocked. Mesh / MagicDNS destinations never reach this
// helper (they stay on the mesh TUN).
func (d Dialer) ExchangeDNS(ctx context.Context, dest netip.AddrPort, query []byte) ([]byte, error) {
	if !d.Cfg.Enabled() {
		return nil, errors.New("forced upstream is off")
	}
	if dest.Port() != dnsPort {
		return nil, fmt.Errorf("not DNS port %d", dest.Port())
	}
	dd := d
	dd.Cfg.FailClosed = true
	dd.Direct = nil

	if dd.Cfg.Kind == KindSOCKS5 {
		resp, err := dd.exchangeDNSUDP(ctx, dest, query)
		if err == nil {
			noteDNS("udp")
			return resp, nil
		}
		if !errors.Is(err, ErrUDPNotSupported) && ctx.Err() != nil {
			noteDNS("blocked")
			return nil, err
		}
	}
	resp, err := dd.exchangeDNSTCP(ctx, dest, query)
	if err != nil {
		noteDNS("blocked")
		return nil, err
	}
	noteDNS("tcp")
	return resp, nil
}

func (d Dialer) exchangeDNSUDP(ctx context.Context, dest netip.AddrPort, query []byte) ([]byte, error) {
	pc, err := d.DialUDP(ctx, dest)
	if err != nil {
		return nil, err
	}
	defer pc.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = pc.SetDeadline(deadline)
	}
	if _, err := pc.WriteTo(query, addrPortAddr{dest}); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	n, _, err := pc.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

func (d Dialer) exchangeDNSTCP(ctx context.Context, dest netip.AddrPort, query []byte) ([]byte, error) {
	c, err := d.DialTCP(ctx, net.JoinHostPort(dest.Addr().String(), "53"))
	if err != nil {
		return nil, err
	}
	defer c.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(deadline)
	} else {
		_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	}
	if err := WriteTCPMessage(c, query); err != nil {
		return nil, err
	}
	return ReadTCPMessage(c)
}

// WriteTCPMessage writes a DNS message with the RFC 7766 16-bit length prefix.
func WriteTCPMessage(w io.Writer, msg []byte) error {
	if len(msg) > 65535 {
		return errors.New("dns message too large")
	}
	var hdr [2]byte
	binary.BigEndian.PutUint16(hdr[:], uint16(len(msg)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err := w.Write(msg)
	return err
}

// ReadTCPMessage reads one RFC 7766 framed DNS message.
func ReadTCPMessage(r io.Reader) ([]byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[:]))
	if n == 0 {
		return nil, errors.New("empty dns message")
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

var (
	dnsMu    sync.Mutex
	dnsState = "off"
)

func noteDNS(state string) {
	dnsMu.Lock()
	dnsState = state
	dnsMu.Unlock()
}

func resetDNSState() {
	dnsMu.Lock()
	dnsState = "off"
	dnsMu.Unlock()
}

// DNSState is "off", "pending", "udp", "tcp", or "blocked".
func DNSState() string {
	dnsMu.Lock()
	defer dnsMu.Unlock()
	if !Get().Enabled() {
		return "off"
	}
	if dnsState == "off" {
		return "pending"
	}
	return dnsState
}
