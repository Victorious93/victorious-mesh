// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const nicID = 1

// Stack is a userspace TCP/UDP endpoint that dials the upstream for
// every accepted flow. Mesh packets never enter this stack.
type Stack struct {
	ep     *channel.Endpoint
	stk    *stack.Stack
	dialer Dialer
	write  func([]byte)
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

func NewStack(mtu uint32, dialer Dialer, writeBack func([]byte)) (*Stack, error) {
	if mtu == 0 {
		mtu = 1280
	}
	ctx, cancel := context.WithCancel(context.Background())
	ep := channel.New(512, mtu, "")
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := s.CreateNIC(nicID, ep); err != nil {
		cancel()
		return nil, fmt.Errorf("create nic: %s", err)
	}
	s.SetPromiscuousMode(nicID, true)
	s.SetSpoofing(nicID, true)
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	out := &Stack{ep: ep, stk: s, dialer: dialer, write: writeBack, cancel: cancel}

	tcpFwd := tcp.NewForwarder(s, 0, 1024, out.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, out.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	out.wg.Add(1)
	go out.pumpOutbound(ctx)
	return out, nil
}

func (s *Stack) HandleInbound(pkt []byte) {
	if len(pkt) < 1 {
		return
	}
	if IsIPv6(pkt) && IPv6State() == IPv6Blocked {
		return
	}
	var proto tcpip.NetworkProtocolNumber
	switch pkt[0] >> 4 {
	case 4:
		proto = ipv4.ProtocolNumber
	case 6:
		proto = ipv6.ProtocolNumber
	default:
		return
	}
	pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), pkt...))})
	s.ep.InjectInbound(proto, pb)
}

func (s *Stack) Close() {
	s.cancel()
	s.stk.Close()
	s.ep.Close()
	s.wg.Wait()
}

func (s *Stack) pumpOutbound(ctx context.Context) {
	defer s.wg.Done()
	for {
		pkt := s.ep.ReadContext(ctx)
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		if s.write != nil && view.Size() > 0 {
			s.write(view.AsSlice())
		}
		view.Release()
		pkt.DecRef()
	}
}

func (s *Stack) handleTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		r.Complete(true)
		return
	}
	r.Complete(false)
	local := gonet.NewTCPConn(&wq, ep)
	dest := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer local.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		up, derr := s.dialer.DialTCP(ctx, dest)
		cancel()
		if derr != nil {
			log.Printf("forced upstream tcp %s: %v", dest, derr)
			return
		}
		defer up.Close()
		copyBoth(local, up)
	}()
}

func (s *Stack) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		return false
	}
	local := gonet.NewUDPConn(&wq, ep)
	ip, perr := netip.ParseAddr(id.LocalAddress.String())
	if perr != nil {
		local.Close()
		return false
	}
	dest := netip.AddrPortFrom(ip, id.LocalPort)
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer local.Close()
		if dest.Port() == dnsPort {
			s.proxyDNS(local, dest)
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		up, derr := s.dialer.DialUDP(ctx, dest)
		cancel()
		if derr != nil {
			log.Printf("forced upstream udp %s: %v", dest, derr)
			return
		}
		defer up.Close()
		copyUDP(local, up)
	}()
	return true
}

func (s *Stack) proxyDNS(local net.PacketConn, dest netip.AddrPort) {
	buf := make([]byte, 4096)
	_ = local.SetDeadline(time.Now().Add(15 * time.Second))
	n, addr, err := local.ReadFrom(buf)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	resp, err := s.dialer.ExchangeDNS(ctx, dest, buf[:n])
	cancel()
	if err != nil {
		log.Printf("forced upstream dns %s: %v", dest, err)
		return
	}
	_, _ = local.WriteTo(resp, addr)
}

func copyBoth(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); io.Copy(a, b); a.Close(); b.Close() }()
	go func() { defer wg.Done(); io.Copy(b, a); a.Close(); b.Close() }()
	wg.Wait()
}

func copyUDP(local net.PacketConn, up net.PacketConn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		buf := make([]byte, 65535)
		for {
			n, addr, err := local.ReadFrom(buf)
			if err != nil {
				return
			}
			if _, err := up.WriteTo(buf[:n], addr); err != nil {
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		buf := make([]byte, 65535)
		for {
			n, addr, err := up.ReadFrom(buf)
			if err != nil {
				return
			}
			if _, err := local.WriteTo(buf[:n], addr); err != nil {
				return
			}
		}
	}()
	wg.Wait()
}
