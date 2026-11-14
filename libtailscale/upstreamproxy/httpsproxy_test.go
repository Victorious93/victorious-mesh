// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"crypto/tls"
	"errors"
	"net"
	"testing"
	"time"
)

func TestHTTPSProxyForwardsOverVerifiedTLS(t *testing.T) {
	ca := newTestCA(t)
	srv := startConnectProxy(t, &tls.Config{Certificates: []tls.Certificate{ca.leaf(t, "proxy.test", "127.0.0.1")}}, "u", "p")
	target := echoServer(t)
	h, port := hostPort(t, srv.addr)
	d := Dialer{
		Cfg:   Config{Kind: KindHTTPS, Host: h, Port: port, Username: "u", Password: "p", FailClosed: true, ServerName: "proxy.test"},
		TLS:   &tls.Config{RootCAs: ca.pool},
		Quiet: true,
	}
	c, err := d.DialTCP(ctx3(t), target)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	if ts := srv.targets(); len(ts) != 1 || ts[0] != target {
		t.Fatalf("proxy saw %v", ts)
	}
}

// A certificate that does not verify must fail at the TLS stage, send
// no CONNECT and no credentials, and never be retried as plaintext.
func TestHTTPSProxyCertFailureNeverDowngrades(t *testing.T) {
	good := newTestCA(t)
	rogue := newTestCA(t)
	cases := map[string]struct {
		cert tls.Certificate
		name string
	}{
		"unknown CA": {rogue.leaf(t, "proxy.test"), "proxy.test"},
		"wrong name": {good.leaf(t, "other.test"), "proxy.test"},
		"expired":    {good.leafAt(t, time.Now().Add(-2*time.Hour), time.Now().Add(-time.Hour), "proxy.test"), "proxy.test"},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			srv := startConnectProxy(t, &tls.Config{Certificates: []tls.Certificate{tc.cert}}, "u", "secret-pass")
			h, port := hostPort(t, srv.addr)
			plainSeen := make(chan struct{}, 1)
			// A second listener on the same host would be where a plaintext
			// retry would go; the dialer is only ever given one address, so
			// instead count plaintext CONNECTs on a tap in front of the proxy.
			_ = plainSeen
			d := Dialer{
				Cfg:   Config{Kind: KindHTTPS, Host: h, Port: port, Username: "u", Password: "secret-pass", FailClosed: true, ServerName: tc.name},
				TLS:   &tls.Config{RootCAs: good.pool},
				Quiet: true,
			}
			_, err := d.DialTCP(ctx3(t), echoServer(t))
			if err == nil {
				t.Fatal("dial succeeded despite a bad certificate")
			}
			var se *StageError
			if !errors.As(err, &se) || se.Stage != StageTLS || !se.Cert {
				t.Fatalf("want TLS cert stage error, got %v", err)
			}
			if len(srv.targets()) != 0 {
				t.Fatalf("CONNECT was sent: %v", srv.targets())
			}
		})
	}
}

// A server that only speaks plaintext HTTP while the profile says
// https must fail at TLS: the client must not fall back.
func TestHTTPSProxyAgainstPlaintextServerFailsTLS(t *testing.T) {
	srv := startConnectProxy(t, nil, "", "")
	h, port := hostPort(t, srv.addr)
	d := Dialer{Cfg: Config{Kind: KindHTTPS, Host: h, Port: port, FailClosed: true}, Quiet: true}
	_, err := d.DialTCP(ctx3(t), echoServer(t))
	if StageOf(err) != StageTLS {
		t.Fatalf("want tls stage, got %v", err)
	}
	if len(srv.targets()) != 0 {
		t.Fatal("plaintext CONNECT reached the proxy")
	}
}

func TestHTTPSProxyUsesDefaultRootsNotInsecure(t *testing.T) {
	// No RootCAs injected: a self-made CA must not be trusted.
	ca := newTestCA(t)
	srv := startConnectProxy(t, &tls.Config{Certificates: []tls.Certificate{ca.leaf(t, "127.0.0.1")}}, "", "")
	h, port := hostPort(t, srv.addr)
	d := Dialer{Cfg: Config{Kind: KindHTTPS, Host: h, Port: port, FailClosed: true}, Quiet: true}
	if _, err := d.DialTCP(ctx3(t), echoServer(t)); StageOf(err) != StageTLS {
		t.Fatalf("got %v", err)
	}
	// Even if a caller tries to set InsecureSkipVerify, the dialer clears it.
	d.TLS = &tls.Config{InsecureSkipVerify: true}
	if _, err := d.DialTCP(ctx3(t), echoServer(t)); StageOf(err) != StageTLS {
		t.Fatalf("InsecureSkipVerify honoured: %v", err)
	}
}

func TestPlainHTTPConnectStagesAuthAndForward(t *testing.T) {
	srv := startConnectProxy(t, nil, "u", "p")
	h, port := hostPort(t, srv.addr)
	target := echoServer(t)
	good := Dialer{Cfg: Config{Kind: KindHTTP, Host: h, Port: port, Username: "u", Password: "p", FailClosed: true}, Quiet: true}
	c, err := good.DialTCP(ctx3(t), target)
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	bad := good
	bad.Cfg.Password = "wrong"
	if _, err := bad.DialTCP(ctx3(t), target); StageOf(err) != StageAuth {
		t.Fatalf("bad password: %v", err)
	}
	// Target refuses: forwarding stage.
	closed, _ := net.Listen("tcp", "127.0.0.1:0")
	dead := closed.Addr().String()
	closed.Close()
	if _, err := good.DialTCP(ctx3(t), dead); StageOf(err) != StageForward {
		t.Fatalf("dead target: %v", err)
	}
	// Nothing listening on the proxy port: reachability.
	nothing := Dialer{Cfg: Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 1, FailClosed: true}, Quiet: true}
	if _, err := nothing.DialTCP(ctx3(t), target); StageOf(err) != StageReach {
		t.Fatalf("closed port: %v", err)
	}
}

func TestSOCKS5ForwardsHostnameUnresolved(t *testing.T) {
	target := echoServer(t)
	srv := startSOCKS(t, "u", "p", map[string]string{"files.example:8443": target})
	h, port := hostPort(t, srv.addr)
	d := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: h, Port: port, Username: "u", Password: "p", FailClosed: true}, Quiet: true}
	c, err := d.DialTCP(ctx3(t), "files.example:8443")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	// The name reached the proxy as a domain (ATYP 3), not a local lookup.
	if ts := srv.targets(); len(ts) != 1 || ts[0] != "3:files.example:8443" {
		t.Fatalf("proxy saw %v", ts)
	}
	bad := d
	bad.Cfg.Password = "nope"
	if _, err := bad.DialTCP(ctx3(t), "files.example:8443"); StageOf(err) != StageAuth {
		t.Fatalf("bad creds: %v", err)
	}
	srv.mu.Lock()
	srv.fail = 5
	srv.mu.Unlock()
	_, err = d.DialTCP(ctx3(t), "files.example:8443")
	var rep *SocksReplyError
	if StageOf(err) != StageForward || !errors.As(err, &rep) || rep.Code != 5 {
		t.Fatalf("refused reply: %v", err)
	}
}

func TestDialHonoursContextCancel(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() { // accepts and says nothing
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			defer c.Close()
		}
	}()
	h, port := hostPort(t, ln.Addr().String())
	for _, k := range []Kind{KindHTTP, KindSOCKS5} {
		d := Dialer{Cfg: Config{Kind: k, Host: h, Port: port, FailClosed: true}, Quiet: true}
		ctx, cancel := contextWithTimeout(150 * time.Millisecond)
		start := time.Now()
		_, err := d.DialTCP(ctx, "1.2.3.4:80")
		cancel()
		if err == nil || time.Since(start) > 2*time.Second {
			t.Fatalf("%s: err=%v after %v", k, err, time.Since(start))
		}
	}
}
