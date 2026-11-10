// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"time"
)

// FlowDialer opens the outside leg of one TCP or UDP flow that the
// userspace stack accepted. Dialer (proxy) and DirectDialer implement it.
type FlowDialer interface {
	DialTCP(ctx context.Context, dest string) (net.Conn, error)
	DialUDP(ctx context.Context, dest netip.AddrPort) (net.PacketConn, error)
	ExchangeDNS(ctx context.Context, dest netip.AddrPort, query []byte) ([]byte, error)
}

// Resolver picks the FlowDialer for a flow. proto is 6 (TCP) or 17
// (UDP); src is the app side, dst the remote side. Returning nil
// refuses the flow (fail-closed).
type Resolver func(proto uint8, src, dst netip.AddrPort) FlowDialer

var _ FlowDialer = Dialer{}
var _ FlowDialer = DirectDialer{}

// ErrNoDirectDial is returned by a DirectDialer with no Dial func.
var ErrNoDirectDial = errors.New("direct dial unavailable")

// DirectDialer relays a flow straight out of the phone's Wi-Fi or
// cellular link. Dial must return protected sockets (VpnService.protect);
// it is used by per-app routing for apps on the default route when the
// tunnel captures all traffic only because other apps use a proxy.
type DirectDialer struct {
	Dial DialFunc
}

func (d DirectDialer) DialTCP(ctx context.Context, dest string) (net.Conn, error) {
	if d.Dial == nil {
		return nil, ErrNoDirectDial
	}
	return d.Dial(ctx, "tcp", dest)
}

func (d DirectDialer) DialUDP(ctx context.Context, dest netip.AddrPort) (net.PacketConn, error) {
	if d.Dial == nil {
		return nil, ErrNoDirectDial
	}
	c, err := d.Dial(ctx, "udp", dest.String())
	if err != nil {
		return nil, err
	}
	return connectedPacketConn{Conn: c}, nil
}

func (d DirectDialer) ExchangeDNS(ctx context.Context, dest netip.AddrPort, query []byte) ([]byte, error) {
	if dest.Port() != dnsPort {
		return nil, fmt.Errorf("not DNS port %d", dest.Port())
	}
	pc, err := d.DialUDP(ctx, dest)
	if err != nil {
		return nil, err
	}
	defer pc.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = pc.SetDeadline(deadline)
	} else {
		_ = pc.SetDeadline(time.Now().Add(10 * time.Second))
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

// connectedPacketConn adapts a connected UDP socket to net.PacketConn.
// WriteTo ignores addr: the socket only talks to its one peer.
type connectedPacketConn struct {
	net.Conn
}

func (c connectedPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, err := c.Conn.Read(p)
	return n, c.Conn.RemoteAddr(), err
}

func (c connectedPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) {
	return c.Conn.Write(p)
}
