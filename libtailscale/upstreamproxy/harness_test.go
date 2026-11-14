// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---------------------------------------------------------------- certs

type testCA struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
	pool *x509.CertPool
}

func newTestCA(t testing.TB) *testCA {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test CA"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return &testCA{cert: cert, key: key, pool: pool}
}

func (ca *testCA) leaf(t testing.TB, names ...string) tls.Certificate {
	return ca.leafAt(t, time.Now().Add(-time.Hour), time.Now().Add(time.Hour), names...)
}

func (ca *testCA) leafAt(t testing.TB, nb, na time.Time, names ...string) tls.Certificate {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: names[0]},
		NotBefore: nb, NotAfter: na, KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	for _, n := range names {
		if ip := net.ParseIP(n); ip != nil {
			tpl.IPAddresses = append(tpl.IPAddresses, ip)
		} else {
			tpl.DNSNames = append(tpl.DNSNames, n)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

func (ca *testCA) pem() []byte {
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: ca.cert.Raw})
}

// ---------------------------------------------------------- echo target

// echoServer is the "internet": it echoes after prefixing a banner.
func echoServer(t testing.TB) string {
	t.Helper()
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
			go func() {
				defer c.Close()
				io.WriteString(c, "hello-from-target\n")
				io.Copy(c, c)
			}()
		}
	}()
	return ln.Addr().String()
}

// -------------------------------------------------- HTTP CONNECT proxy

type connectProxy struct {
	addr     string
	mu       sync.Mutex
	requests []string // CONNECT targets seen
	plain    int      // connections that sent plaintext when TLS was expected
	user     string
	pass     string
}

// startConnectProxy serves HTTP CONNECT; tlsCfg != nil serves it over TLS.
func startConnectProxy(t testing.TB, tlsCfg *tls.Config, user, pass string) *connectProxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if tlsCfg != nil {
		ln = tls.NewListener(ln, tlsCfg)
	}
	p := &connectProxy{addr: ln.Addr().String(), user: user, pass: pass}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.serve(c)
		}
	}()
	return p
}

func (p *connectProxy) serve(c net.Conn) {
	defer c.Close()
	br := bufio.NewReader(c)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.Method != http.MethodConnect {
		io.WriteString(c, "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n")
		return
	}
	if p.user != "" {
		u, pw, ok := req.BasicAuth()
		if v := req.Header.Get("Proxy-Authorization"); v != "" {
			r2, _ := http.NewRequest("GET", "/", nil)
			r2.Header.Set("Authorization", v)
			u, pw, ok = r2.BasicAuth()
		}
		if !ok || u != p.user || pw != p.pass {
			io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=x\r\nContent-Length: 0\r\n\r\n")
			return
		}
	}
	p.mu.Lock()
	p.requests = append(p.requests, req.Host)
	p.mu.Unlock()
	up, err := net.Dial("tcp", req.Host)
	if err != nil {
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
		return
	}
	defer up.Close()
	io.WriteString(c, "HTTP/1.1 200 Connection established\r\n\r\n")
	done := make(chan struct{}, 2)
	go func() { io.Copy(up, br); done <- struct{}{} }()
	go func() { io.Copy(c, up); done <- struct{}{} }()
	<-done
}

func (p *connectProxy) targets() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.requests...)
}

// ----------------------------------------------------- SOCKS5 "Tor" proxy

type socksProxy struct {
	addr  string
	mu    sync.Mutex
	seen  []string // "atyp:host:port" per CONNECT
	user  string
	pass  string
	route map[string]string // virtual host -> real addr ("hello.test:80" -> echo)
	fail  byte              // reply code to send instead of success
}

func startSOCKS(t testing.TB, user, pass string, route map[string]string) *socksProxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &socksProxy{addr: ln.Addr().String(), user: user, pass: pass, route: route}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.serve(c)
		}
	}()
	return p
}

func (p *socksProxy) serve(c net.Conn) {
	defer c.Close()
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return
	}
	methods := make([]byte, hdr[1])
	io.ReadFull(c, methods)
	if p.user != "" {
		if !bytes.Contains(methods, []byte{2}) {
			c.Write([]byte{5, 0xFF})
			return
		}
		c.Write([]byte{5, 2})
		b := make([]byte, 2)
		io.ReadFull(c, b)
		u := make([]byte, b[1])
		io.ReadFull(c, u)
		pl := make([]byte, 1)
		io.ReadFull(c, pl)
		pw := make([]byte, pl[0])
		io.ReadFull(c, pw)
		if string(u) != p.user || string(pw) != p.pass {
			c.Write([]byte{1, 1})
			return
		}
		c.Write([]byte{1, 0})
	} else {
		if !bytes.Contains(methods, []byte{0}) {
			c.Write([]byte{5, 0xFF})
			return
		}
		c.Write([]byte{5, 0})
	}
	rq := make([]byte, 4)
	if _, err := io.ReadFull(c, rq); err != nil {
		return
	}
	var host string
	switch rq[3] {
	case 1:
		b := make([]byte, 4)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		io.ReadFull(c, l)
		b := make([]byte, l[0])
		io.ReadFull(c, b)
		host = string(b)
	}
	pb := make([]byte, 2)
	io.ReadFull(c, pb)
	port := binary.BigEndian.Uint16(pb)
	target := fmt.Sprintf("%s:%d", host, port)
	p.mu.Lock()
	p.seen = append(p.seen, fmt.Sprintf("%d:%s", rq[3], target))
	fail := p.fail
	p.mu.Unlock()
	if fail != 0 {
		c.Write([]byte{5, fail, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	real := target
	if r, ok := p.route[target]; ok {
		real = r
	} else if len(p.route) > 0 {
		c.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	up, err := net.Dial("tcp", real)
	if err != nil {
		c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	done := make(chan struct{}, 2)
	go func() { io.Copy(up, c); done <- struct{}{} }()
	go func() { io.Copy(c, up); done <- struct{}{} }()
	<-done
}

func (p *socksProxy) targets() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.seen...)
}

func readLine(t testing.TB, c net.Conn) string {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
	s, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return strings.TrimSpace(s)
}

func hostPort(t testing.TB, addr string) (string, int32) {
	h, p, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatal(err)
	}
	var n int32
	fmt.Sscan(p, &n)
	return h, n
}

func contextWithTimeout(d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), d)
}
