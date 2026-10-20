package upstreamproxy

import (
	"bufio"
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"
)

func TestDNSStatePendingWhenEnabled(t *testing.T) {
	if err := Set(Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 18080}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Clear)
	if DNSState() != "pending" {
		t.Fatalf("dns state %s", DNSState())
	}
	Clear()
	if DNSState() != "off" {
		t.Fatalf("cleared dns state %s", DNSState())
	}
}

func TestTCPMessageRoundTrip(t *testing.T) {
	q := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01}
	c1, c2 := net.Pipe()
	t.Cleanup(func() { c1.Close(); c2.Close() })
	go func() {
		_ = WriteTCPMessage(c1, q)
	}()
	got, err := ReadTCPMessage(c2)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(q) {
		t.Fatalf("got %x", got)
	}
}

func TestExchangeDNSTCPViaHTTPConnect(t *testing.T) {
	query := []byte{0xaa, 0xbb, 0x01, 0x00}
	reply := []byte{0xaa, 0xbb, 0x81, 0x80}
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
		br := bufio.NewReader(c)
		req, err := http.ReadRequest(br)
		if err != nil || req.Method != http.MethodConnect {
			return
		}
		io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
		msg, err := ReadTCPMessage(c)
		if err != nil || string(msg) != string(query) {
			return
		}
		_ = WriteTCPMessage(c, reply)
	}()

	host, port, _ := net.SplitHostPort(ln.Addr().String())
	cfg := Config{Kind: KindHTTP, Host: host, Port: mustPort(port)}
	if err := Set(cfg); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Clear)
	d := Dialer{Cfg: cfg}
	var directHits atomic.Int32
	d.Direct = func(ctx context.Context, network, address string) (net.Conn, error) {
		directHits.Add(1)
		return nil, io.ErrClosedPipe
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	dest := netip.MustParseAddrPort("1.1.1.1:53")
	got, err := d.ExchangeDNS(ctx, dest, query)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(reply) {
		t.Fatalf("reply %x", got)
	}
	if directHits.Load() != 0 {
		t.Fatal("DNS must not fall back to Direct")
	}
	if DNSState() != "tcp" {
		t.Fatalf("dns state %s", DNSState())
	}
}

func TestExchangeDNSFailClosedWhenProxyDown(t *testing.T) {
	cfg := Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 19999, FailClosed: false}
	if err := Set(cfg); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Clear)
	d := Dialer{
		Cfg: cfg,
		Direct: func(ctx context.Context, network, address string) (net.Conn, error) {
			t.Fatal("Direct must not be used for DNS")
			return nil, io.ErrClosedPipe
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	_, err := d.ExchangeDNS(ctx, netip.MustParseAddrPort("8.8.8.8:53"), []byte{1, 2, 3})
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestDialTCPPort53NeverUsesDirect(t *testing.T) {
	cfg := Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 19997, FailClosed: false}
	if err := Set(cfg); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(Clear)
	d := Dialer{
		Cfg: cfg,
		Direct: func(ctx context.Context, network, address string) (net.Conn, error) {
			t.Fatal("Direct must not be used for TCP DNS")
			return nil, io.ErrClosedPipe
		},
	}
	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	_, err := d.DialTCP(ctx, "1.1.1.1:53")
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestWriteTCPMessageLengthPrefix(t *testing.T) {
	msg := []byte{9, 8, 7}
	c1, c2 := net.Pipe()
	t.Cleanup(func() { c1.Close(); c2.Close() })
	go func() { _ = WriteTCPMessage(c1, msg) }()
	var hdr [2]byte
	if _, err := io.ReadFull(c2, hdr[:]); err != nil {
		t.Fatal(err)
	}
	if binary.BigEndian.Uint16(hdr[:]) != 3 {
		t.Fatalf("len %d", binary.BigEndian.Uint16(hdr[:]))
	}
}
