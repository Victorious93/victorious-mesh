// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"
)

func TestEngineDNSKind(t *testing.T) {
	cases := map[string]string{
		"9.9.9.9:53":                 "dns-tcp",
		"192.168.1.1:53":             "dns-tcp",
		"[2620:fe::fe]:53":           "dns-tcp",
		"dns.example:53":             "dns-tcp",
		"1.1.1.1:853":                "dns-tls",
		"1.1.1.1:443":                "doh",
		"8.8.8.8:443":                "doh",
		"[2606:4700:4700::1111]:443": "doh",
		"203.0.113.5:443":            "", // e.g. the Headscale server
		"headscale.example:443":      "",
		"100.100.100.100:53":         "", // mesh resolver stays on the mesh
		"100.64.0.9:53":              "",
		"[fd7a:115c:a1e0::53]:53":    "",
		"1.1.1.1:80":                 "",
		"garbage":                    "",
	}
	for addr, want := range cases {
		if got := EngineDNSKind(addr); got != want {
			t.Errorf("EngineDNSKind(%q) = %q, want %q", addr, got, want)
		}
	}
}

type fakeHTTPProxy struct {
	ln      net.Listener
	mu      sync.Mutex
	targets []string
}

func startFakeHTTPProxy(t *testing.T) *fakeHTTPProxy {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &fakeHTTPProxy{ln: ln}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				req, err := http.ReadRequest(bufio.NewReader(c))
				if err != nil {
					return
				}
				p.mu.Lock()
				p.targets = append(p.targets, req.Host)
				p.mu.Unlock()
				io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\nok")
				time.Sleep(50 * time.Millisecond)
			}(c)
		}
	}()
	return p
}

func (p *fakeHTTPProxy) cfg(t *testing.T) Config {
	host, port, _ := net.SplitHostPort(p.ln.Addr().String())
	return Config{Kind: KindHTTP, Host: host, Port: mustPort(port)}
}

func (p *fakeHTTPProxy) seen() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.targets...)
}

type directRecorder struct {
	mu    sync.Mutex
	calls []string
}

func (d *directRecorder) dial(_ context.Context, network, address string) (net.Conn, error) {
	d.mu.Lock()
	d.calls = append(d.calls, network+" "+address)
	d.mu.Unlock()
	a, b := net.Pipe()
	go b.Close()
	return a, nil
}

func (d *directRecorder) n() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.calls)
}

func ctx3(t *testing.T) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func TestEngineDNSGuardOffIsUnchanged(t *testing.T) {
	direct := &directRecorder{}
	g := EngineDNSGuard{Cfg: func() Config { return Config{} }, Direct: direct.dial,
		ProxyDial: func(context.Context, string, string) (net.Conn, error) {
			t.Fatal("proxy used while forced proxy is off")
			return nil, nil
		}}
	for _, a := range []string{"9.9.9.9:53", "1.1.1.1:443", "203.0.113.5:443"} {
		c, err := g.Dial(ctx3(t), "tcp4", a)
		if err != nil {
			t.Fatal(err)
		}
		c.Close()
	}
	if direct.n() != 3 {
		t.Fatalf("direct calls = %d, want 3", direct.n())
	}
}

func TestEngineDNSGuardSendsDNSThroughProxy(t *testing.T) {
	p := startFakeHTTPProxy(t)
	cfg := p.cfg(t)
	direct := &directRecorder{}
	g := EngineDNSGuard{Cfg: func() Config { return cfg }, ProxyDial: defaultDial, Direct: direct.dial}

	for _, a := range []string{"9.9.9.9:53", "1.1.1.1:443", "1.1.1.1:853"} {
		c, err := g.Dial(ctx3(t), "tcp4", a)
		if err != nil {
			t.Fatalf("%s: %v", a, err)
		}
		c.Close()
	}
	if direct.n() != 0 {
		t.Fatalf("engine DNS went direct %d times", direct.n())
	}
	deadline := time.Now().Add(2 * time.Second)
	for len(p.seen()) < 3 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	got := map[string]bool{}
	for _, s := range p.seen() {
		got[s] = true
	}
	for _, want := range []string{"9.9.9.9:53", "1.1.1.1:443", "1.1.1.1:853"} {
		if !got[want] {
			t.Errorf("proxy never saw CONNECT %s (saw %v)", want, p.seen())
		}
	}

	// Non-DNS system dials (control server, DERP, ...) are untouched.
	c, err := g.Dial(ctx3(t), "tcp4", "203.0.113.5:443")
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if direct.n() != 1 {
		t.Fatalf("non-DNS dial should go direct once, got %d", direct.n())
	}
}

func TestEngineDNSGuardBlocksWhenProxyDown(t *testing.T) {
	// Even with FailClosed off in the saved config, engine DNS never
	// falls back to the underlay.
	cfg := Config{Kind: KindSOCKS5, Host: "127.0.0.1", Port: 1080, FailClosed: false}
	if !cfg.Enabled() {
		t.Fatal("test config must count as forced-on")
	}
	direct := &directRecorder{}
	g := EngineDNSGuard{
		Cfg:       func() Config { return cfg },
		ProxyDial: func(context.Context, string, string) (net.Conn, error) { return nil, errors.New("proxy down") },
		Direct:    direct.dial,
	}
	for _, a := range []string{"9.9.9.9:53", "8.8.8.8:443"} {
		_, err := g.Dial(ctx3(t), "tcp4", a)
		if !errors.Is(err, ErrEngineDNSBlocked) {
			t.Fatalf("%s: err = %v, want ErrEngineDNSBlocked", a, err)
		}
	}
	if direct.n() != 0 {
		t.Fatalf("blocked DNS leaked direct %d times", direct.n())
	}
	dnsMu.Lock()
	st := dnsState
	dnsMu.Unlock()
	if st != "blocked" {
		t.Fatalf("DNS state = %q, want blocked", st)
	}
}

func TestEngineDNSGuardRefusesUDP(t *testing.T) {
	cfg := Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 8080}
	direct := &directRecorder{}
	g := EngineDNSGuard{Cfg: func() Config { return cfg }, ProxyDial: defaultDial, Direct: direct.dial}
	if _, err := g.Dial(ctx3(t), "udp4", "9.9.9.9:53"); !errors.Is(err, ErrEngineDNSBlocked) {
		t.Fatalf("err = %v", err)
	}
	if direct.n() != 0 {
		t.Fatal("UDP engine DNS went direct")
	}
}
