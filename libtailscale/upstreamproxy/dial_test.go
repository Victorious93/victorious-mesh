package upstreamproxy

import (
	"bufio"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestDialHTTPConnectAppliesCredentials(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	gotAuth := make(chan string, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		br := bufio.NewReader(c)
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}
		gotAuth <- req.Header.Get("Proxy-Authorization")
		if req.Method != http.MethodConnect || req.Host != "example.com:443" {
			io.WriteString(c, "HTTP/1.1 400 Bad Request\r\n\r\n")
			return
		}
		io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
		io.WriteString(c, "hello")
	}()

	host, port, _ := net.SplitHostPort(ln.Addr().String())
	d := Dialer{Cfg: Config{Kind: KindHTTP, Host: host, Port: mustPort(port), Username: "don", Password: "s3cret"}}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	c, err := d.DialTCP(ctx, "example.com:443")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	buf := make([]byte, 5)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "hello" {
		t.Fatalf("payload %q", buf)
	}
	select {
	case a := <-gotAuth:
		want := "Basic " + base64.StdEncoding.EncodeToString([]byte("don:s3cret"))
		if a != want {
			t.Fatalf("auth %q", a)
		}
	case <-time.After(time.Second):
		t.Fatal("no auth header")
	}
}

func TestDialHTTPConnectFailClosedOnBadStatus(t *testing.T) {
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
		_, _ = http.ReadRequest(br)
		io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	d := Dialer{Cfg: Config{Kind: KindHTTP, Host: host, Port: mustPort(port)}}
	_, err = d.DialTCP(context.Background(), "example.com:443")
	if err == nil || !strings.Contains(err.Error(), "407") {
		t.Fatalf("want 407, got %v", err)
	}
}

func TestDialTCPFailClosedVsFallback(t *testing.T) {
	d := Dialer{
		Cfg: Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 1024, FailClosed: true},
		Direct: func(ctx context.Context, network, address string) (net.Conn, error) {
			t.Fatal("fail-closed must not dial direct")
			return nil, nil
		},
	}
	if _, err := d.DialTCP(context.Background(), "example.com:443"); err == nil {
		t.Fatal("expected proxy error")
	}
	var hit bool
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		c, _ := ln.Accept()
		if c != nil {
			c.Close()
		}
	}()
	d.Cfg.FailClosed = false
	d.Direct = func(ctx context.Context, network, address string) (net.Conn, error) {
		hit = true
		return net.Dial(network, ln.Addr().String())
	}
	c, err := d.DialTCP(context.Background(), "example.com:443")
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if !hit {
		t.Fatal("expected direct fallback")
	}
}

func TestDialTCPOffAndUDPHTTP(t *testing.T) {
	d := Dialer{Cfg: Config{}}
	if _, err := d.DialTCP(context.Background(), "example.com:443"); err == nil {
		t.Fatal("off must fail")
	}
	d.Cfg = Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 8080}
	if _, err := d.DialUDP(context.Background(), mustAP("1.2.3.4:53")); err != ErrUDPNotSupported {
		t.Fatalf("http udp: %v", err)
	}
}

func TestSOCKS5HandshakeAndAssociateReject(t *testing.T) {
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
		hdr := make([]byte, 3)
		_, _ = io.ReadFull(c, hdr)
		_, _ = c.Write([]byte{5, 0})
		req := make([]byte, 10)
		_, _ = io.ReadFull(c, req)
		if req[1] != 3 {
			t.Logf("cmd %d", req[1])
		}
		_, _ = c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0}) // command not supported
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	d := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: host, Port: mustPort(port)}}
	_, err = d.DialUDP(context.Background(), mustAP("8.8.8.8:53"))
	if err == nil {
		t.Fatal("associate must fail")
	}
}

func mustPort(p string) int32 {
	var n int32
	for _, c := range p {
		n = n*10 + int32(c-'0')
	}
	return n
}

func mustAP(s string) netip.AddrPort { return netip.MustParseAddrPort(s) }
