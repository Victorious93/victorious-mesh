package upstreamproxy

import (
	"context"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestDestIsIPv6(t *testing.T) {
	if !destIsIPv6("[2001:db8::1]:443") {
		t.Fatal("expected ipv6")
	}
	if destIsIPv6("1.2.3.4:443") {
		t.Fatal("ipv4")
	}
}

func TestDestPort(t *testing.T) {
	p, ok := destPort("1.1.1.1:53")
	if !ok || p != 53 {
		t.Fatalf("%d %v", p, ok)
	}
	p, ok = destPort("[2001:db8::1]:443")
	if !ok || p != 443 {
		t.Fatalf("%d %v", p, ok)
	}
	if _, ok := destPort("no-port"); ok {
		t.Fatal("expected false")
	}
}

func TestDialTCPIPv6NeverUsesDirect(t *testing.T) {
	_ = Set(Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 19998, FailClosed: false})
	t.Cleanup(Clear)
	var hits atomic.Int32
	d := Dialer{
		Cfg: Get(),
		Direct: func(ctx context.Context, network, address string) (net.Conn, error) {
			hits.Add(1)
			return nil, io.ErrClosedPipe
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	_, err := d.DialTCP(ctx, "[2001:db8::1]:443")
	if err == nil {
		t.Fatal("expected failure")
	}
	if hits.Load() != 0 {
		t.Fatal("IPv6 must not leak via Direct")
	}
}

func TestIPv6BlockedAfterProxyRejectsCONNECT(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	_ = Set(Config{Kind: KindHTTP, Host: host, Port: mustPort(port)})
	t.Cleanup(Clear)
	d := Dialer{Cfg: Get()}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err = d.DialTCP(ctx, "[2001:db8::2]:443")
	if err == nil {
		t.Fatal("expected CONNECT failure")
	}
	if IPv6State() != IPv6Blocked {
		t.Fatalf("state %s", IPv6State())
	}
	_, err = d.DialTCP(ctx, "[2001:db8::3]:443")
	if !errors.Is(err, ErrIPv6Blocked) {
		t.Fatalf("second dial: %v", err)
	}
}

func TestIPv6RejectedByProxyHeuristic(t *testing.T) {
	if !ipv6RejectedByProxy(errors.New("CONNECT [2001:db8::1]:443: 502 Bad Gateway")) {
		t.Fatal("CONNECT error should count")
	}
	if ipv6RejectedByProxy(errors.New("dial tcp 127.0.0.1:9: connection refused")) {
		t.Fatal("proxy-down dial should not permanently block")
	}
}
