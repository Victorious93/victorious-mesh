// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestQuietDialerLeavesForcedStateAlone(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
			c.Close()
		}
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	// Device-wide forced proxy is on; a per-app proxy (Quiet) is refused
	// on IPv6. The forced-mode IPv6 status must not flip to blocked.
	_ = Set(Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 19997})
	t.Cleanup(Clear)
	d := Dialer{Cfg: Config{Kind: KindHTTP, Host: host, Port: mustPort(port), FailClosed: true}, Quiet: true}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := d.DialTCP(ctx, "[2001:db8::2]:443"); err == nil {
		t.Fatal("expected CONNECT failure")
	}
	if IPv6State() == IPv6Blocked {
		t.Fatal("quiet dialer changed forced IPv6 state")
	}
}

func TestPerAppProxyFailClosedNeverDirect(t *testing.T) {
	hits := 0
	d := Dialer{
		Cfg:   Config{Kind: KindSOCKS5, Host: "127.0.0.1", Port: 9, FailClosed: true},
		Quiet: true,
		Dial: func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("proxy down")
		},
		Direct: func(context.Context, string, string) (net.Conn, error) {
			hits++
			return nil, io.ErrClosedPipe
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := d.DialTCP(ctx, "93.184.216.34:443"); err == nil {
		t.Fatal("expected failure")
	}
	if hits != 0 {
		t.Fatal("fail-closed per-app proxy used the direct path")
	}
}

func TestDirectDialerNilRefuses(t *testing.T) {
	var d DirectDialer
	ctx := context.Background()
	if _, err := d.DialTCP(ctx, "1.1.1.1:443"); !errors.Is(err, ErrNoDirectDial) {
		t.Fatalf("tcp: %v", err)
	}
	if _, err := d.DialUDP(ctx, netip.MustParseAddrPort("1.1.1.1:443")); !errors.Is(err, ErrNoDirectDial) {
		t.Fatalf("udp: %v", err)
	}
	if _, err := d.ExchangeDNS(ctx, netip.MustParseAddrPort("1.1.1.1:80"), []byte{1}); err == nil {
		t.Fatal("non-53 DNS must fail")
	}
}

func TestDirectDialerDNSAndUDP(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("re:"), buf[:n]...), addr)
		}
	}()
	real := pc.LocalAddr().String()
	var dialed []string
	d := DirectDialer{Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		dialed = append(dialed, network+" "+address)
		var nd net.Dialer
		return nd.DialContext(ctx, network, real) // stand-in for the remote
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	resp, err := d.ExchangeDNS(ctx, netip.MustParseAddrPort("9.9.9.9:53"), []byte("q"))
	if err != nil || string(resp) != "re:q" {
		t.Fatalf("dns: %q %v", resp, err)
	}
	if len(dialed) != 1 || dialed[0] != "udp 9.9.9.9:53" {
		t.Fatalf("dialed %v", dialed)
	}
	up, err := d.DialUDP(ctx, netip.MustParseAddrPort("9.9.9.9:4000"))
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	if _, err := up.WriteTo([]byte("x"), nil); err != nil {
		t.Fatal(err)
	}
	_ = up.SetDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 16)
	n, from, err := up.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "re:x" || from == nil {
		t.Fatalf("udp: %q %v %v", buf[:n], from, err)
	}
}

func TestStackResolverRequired(t *testing.T) {
	if _, err := NewStackWithResolver(1280, nil, nil); err == nil {
		t.Fatal("nil resolver must error")
	}
	st, err := NewStackWithResolver(1280, func(uint8, netip.AddrPort, netip.AddrPort) FlowDialer { return nil }, func([]byte) {})
	if err != nil {
		t.Fatal(err)
	}
	st.Close()
}
