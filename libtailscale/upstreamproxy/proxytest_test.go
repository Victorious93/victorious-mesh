// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestParseProxyURI(t *testing.T) {
	ok := map[string]Config{
		"http://127.0.0.1:8080":          {Kind: KindHTTP, Host: "127.0.0.1", Port: 8080},
		"https://proxy.example:443":      {Kind: KindHTTPS, Host: "proxy.example", Port: 443},
		"socks5://[2001:db8::1]:1080":    {Kind: KindSOCKS5, Host: "2001:db8::1", Port: 1080},
		"socks5h://u:p%40ss@host.test:1": {Kind: KindSOCKS5, Host: "host.test", Port: 1, Username: "u", Password: "p@ss"},
		"HTTP://a.b:65535":               {Kind: KindHTTP, Host: "a.b", Port: 65535},
		"sam://127.0.0.1:7656":           {Kind: KindSAM, Host: "127.0.0.1", Port: 7656, Backend: BackendI2PSAM},
	}
	for in, want := range ok {
		got, err := ParseProxyURI(in)
		if err != nil {
			t.Fatalf("%s: %v", in, err)
		}
		want.FailClosed = true
		if got != want {
			t.Fatalf("%s: got %+v want %+v", in, got, want)
		}
	}
	for _, in := range []string{
		"", "proxy.example:8080", "ftp://x:21", "http://", "http://host", "http://host:0", "http://host:65536",
		"http://host:abc", "http://u@host:80", "http://host:80/path", "socks5://[::1:1080", "http://host:80?x=1",
	} {
		if _, err := ParseProxyURI(in); err == nil {
			t.Errorf("%q accepted", in)
		}
	}
	// https must never come back as plaintext http.
	if c, _ := ParseProxyURI("https://p.example:8443"); c.Kind != KindHTTPS {
		t.Fatalf("https parsed as %v", c.Kind)
	}
}

func TestRedactProxyURIHidesSecrets(t *testing.T) {
	c := Config{Kind: KindSOCKS5, Host: "2001:db8::1", Port: 1080, Username: "alice", Password: "hunter2"}
	s := RedactProxyURI(c, false)
	if strings.Contains(s, "hunter2") || !strings.Contains(s, "alice:***@") || !strings.Contains(s, "[2001:db8::1]:1080") {
		t.Fatal(s)
	}
	if s := RedactProxyURI(c, true); strings.Contains(s, "alice") || strings.Contains(s, "hunter2") {
		t.Fatal(s)
	}
	// Round trip of the redacted form is a valid URI shape.
	if _, err := ParseProxyURI(RedactProxyURI(Config{Kind: KindHTTP, Host: "h", Port: 3}, false)); err != nil {
		t.Fatal(err)
	}
}

// web is a plain HTTP "internet" target that answers GET /.
func webServer(t testing.TB) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "ok")
	}))
	t.Cleanup(srv.Close)
	return srv.Listener.Addr().String()
}

func TestRunTestReportsEachStage(t *testing.T) {
	web := webServer(t)
	wh, wp := hostPort(t, web)
	tgt := TestTarget{Host: wh, Port: int(wp)}

	// Success over HTTP CONNECT with auth.
	px := startConnectProxy(t, nil, "u", "p")
	h, port := hostPort(t, px.addr)
	d := Dialer{Cfg: Config{Kind: KindHTTP, Host: h, Port: port, Username: "u", Password: "p", FailClosed: true}, Quiet: true}
	r := RunTest(ctx3(t), d, tgt)
	if r.Verdict != "ok" || len(r.Steps) != 5 {
		t.Fatalf("%+v", r)
	}

	// Wrong password → authentication, and forwarding never attempted.
	d.Cfg.Password = "x"
	if r := RunTest(ctx3(t), d, tgt); r.Verdict != string(StageAuth) {
		t.Fatalf("%+v", r)
	}

	// Port open but not a proxy → negotiation, not success.
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			io.WriteString(c, "SSH-2.0-banner\r\n")
			c.Close()
		}
	}()
	lh, lp := hostPort(t, ln.Addr().String())
	notProxy := Dialer{Cfg: Config{Kind: KindHTTP, Host: lh, Port: lp, FailClosed: true}, Quiet: true}
	if r := RunTest(ctx3(t), notProxy, tgt); r.Verdict != string(StageNegotiate) || !r.Steps[0].OK {
		t.Fatalf("an open port was treated as a proxy: %+v", r)
	}

	// Closed port → reachability.
	dead := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: "127.0.0.1", Port: 1, FailClosed: true}, Quiet: true}
	if r := RunTest(ctx3(t), dead, tgt); r.Verdict != string(StageReach) {
		t.Fatalf("%+v", r)
	}

	// Proxy fine, target dead → forwarding.
	l2, _ := net.Listen("tcp", "127.0.0.1:0")
	deadAddr := l2.Addr().String()
	l2.Close()
	dh, dp := hostPort(t, deadAddr)
	d.Cfg.Password = "p"
	if r := RunTest(ctx3(t), d, TestTarget{Host: dh, Port: int(dp)}); r.Verdict != string(StageForward) {
		t.Fatalf("%+v", r)
	}

	// Plain SOCKS5 success.
	sk := startSOCKS(t, "", "", nil)
	sh, sp := hostPort(t, sk.addr)
	sd := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: sh, Port: sp, FailClosed: true}, Quiet: true}
	if r := RunTest(ctx3(t), sd, tgt); r.Verdict != "ok" {
		t.Fatalf("%+v", r)
	}
}

func TestRunTestHTTPSProxyCertStage(t *testing.T) {
	ca, rogue := newTestCA(t), newTestCA(t)
	web := webServer(t)
	wh, wp := hostPort(t, web)
	srv := startConnectProxy(t, &tls.Config{Certificates: []tls.Certificate{rogue.leaf(t, "proxy.test")}}, "", "")
	h, port := hostPort(t, srv.addr)
	d := Dialer{Cfg: Config{Kind: KindHTTPS, Host: h, Port: port, ServerName: "proxy.test", FailClosed: true},
		TLS: &tls.Config{RootCAs: ca.pool}, Quiet: true}
	r := RunTest(ctx3(t), d, TestTarget{Host: wh, Port: int(wp)})
	if r.Verdict != string(StageTLS) || !strings.Contains(r.Steps[1].Detail, "never retried without TLS") {
		t.Fatalf("%+v", r)
	}
	good := startConnectProxy(t, &tls.Config{Certificates: []tls.Certificate{ca.leaf(t, "proxy.test")}}, "", "")
	h, port = hostPort(t, good.addr)
	d.Cfg.Host, d.Cfg.Port = h, port
	if r := RunTest(ctx3(t), d, TestTarget{Host: wh, Port: int(wp)}); r.Verdict != "ok" || !strings.Contains(r.Steps[1].Detail, "TLS verified") {
		t.Fatalf("%+v", r)
	}
}

func TestRunTestI2PRequestThroughSAM(t *testing.T) {
	web := webServer(t)
	sam := startFakeSAM(t, map[string]string{"eepsite.i2p": web})
	mgr := NewSAMManager(sam.addr, nil, SAMOptions{})
	defer mgr.Close()
	h, port := hostPort(t, sam.addr)
	d := Dialer{Cfg: Config{Kind: KindSAM, Host: h, Port: port, FailClosed: true, Backend: BackendI2PSAM}, Quiet: true, SAM: mgr}
	r := RunTest(ctx3(t), d, TestTarget{Host: "eepsite.i2p", Port: 80})
	if r.Verdict != "ok" {
		t.Fatalf("%+v", r)
	}
	// An .i2p name the router cannot reach fails at forwarding, with the router's reason.
	r = RunTest(ctx3(t), d, TestTarget{Host: "gone.i2p", Port: 80})
	if r.Verdict != string(StageForward) || !strings.Contains(r.Steps[len(r.Steps)-1].Detail, "CANT_REACH_PEER") {
		t.Fatalf("%+v", r)
	}
}
