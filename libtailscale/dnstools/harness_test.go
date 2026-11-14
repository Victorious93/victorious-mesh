// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package dnstools

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
	"golang.org/x/net/dns/dnsmessage"
)

type testCA struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
	pool *x509.CertPool
}

func newCA(t testing.TB) *testCA {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	der, _ := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	c, _ := x509.ParseCertificate(der)
	p := x509.NewCertPool()
	p.AddCert(c)
	return &testCA{c, key, p}
}

func (ca *testCA) leaf(t testing.TB, names ...string) tls.Certificate {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: names[0]},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
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

type answerFn func(name string, qt dnsmessage.Type) reply

type reply struct {
	rcode dnsmessage.RCode
	ans   []dnsmessage.Resource
	auth  []dnsmessage.Resource
	ad    bool
	tc    bool
}

func mustName(s string) dnsmessage.Name { n, _ := dnsmessage.NewName(s); return n }

func a(name, ip string, ttl uint32) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: mustName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: ttl},
		Body: &dnsmessage.AResource{A: netip.MustParseAddr(ip).As4()}}
}

func soa(zone string, ttl, minimum uint32) dnsmessage.Resource {
	return dnsmessage.Resource{Header: dnsmessage.ResourceHeader{Name: mustName(zone + "."), Type: dnsmessage.TypeSOA, Class: dnsmessage.ClassINET, TTL: ttl},
		Body: &dnsmessage.SOAResource{NS: mustName("ns." + zone + "."), MBox: mustName("h." + zone + "."), Serial: 7, Refresh: 1, Retry: 2, Expire: 3, MinTTL: minimum}}
}

func build(q []byte, fn answerFn) []byte {
	var p dnsmessage.Parser
	h, err := p.Start(q)
	if err != nil {
		return nil
	}
	qs, err := p.Question()
	if err != nil {
		return nil
	}
	r := fn(upstreamproxy.NormalizeName(qs.Name.String()), qs.Type)
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: h.ID, Response: true, RecursionDesired: true, RecursionAvailable: true, RCode: r.rcode, AuthenticData: r.ad, Truncated: r.tc})
	b.EnableCompression()
	b.StartQuestions()
	b.Question(qs)
	b.StartAnswers()
	for _, rr := range r.ans {
		switch body := rr.Body.(type) {
		case *dnsmessage.AResource:
			b.AResource(rr.Header, *body)
		case *dnsmessage.AAAAResource:
			b.AAAAResource(rr.Header, *body)
		case *dnsmessage.MXResource:
			b.MXResource(rr.Header, *body)
		case *dnsmessage.TXTResource:
			b.TXTResource(rr.Header, *body)
		case *dnsmessage.SRVResource:
			b.SRVResource(rr.Header, *body)
		case *dnsmessage.PTRResource:
			b.PTRResource(rr.Header, *body)
		case *dnsmessage.NSResource:
			b.NSResource(rr.Header, *body)
		case *dnsmessage.CNAMEResource:
			b.CNAMEResource(rr.Header, *body)
		case *dnsmessage.SOAResource:
			b.SOAResource(rr.Header, *body)
		case *dnsmessage.UnknownResource:
			b.UnknownResource(rr.Header, *body)
		}
	}
	b.StartAuthorities()
	for _, rr := range r.auth {
		if body, ok := rr.Body.(*dnsmessage.SOAResource); ok {
			b.SOAResource(rr.Header, *body)
		}
	}
	out, _ := b.Finish()
	return out
}

type fakeDNS struct {
	udpAddr, tcpAddr string
	queries          atomic.Int64
	mu               sync.Mutex
	names            []string
}

func (f *fakeDNS) seen(name string) { f.mu.Lock(); f.names = append(f.names, name); f.mu.Unlock() }
func (f *fakeDNS) seenNames() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.names...)
}

func wrapFn(f *fakeDNS, fn answerFn) answerFn {
	return func(n string, t dnsmessage.Type) reply {
		f.queries.Add(1)
		f.seen(n)
		return fn(n, t)
	}
}

func startUDP(t testing.TB, fn answerFn) *fakeDNS {
	f := &fakeDNS{}
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	f.udpAddr = pc.LocalAddr().String()
	wf := wrapFn(f, fn)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if resp := build(buf[:n], wf); resp != nil {
				pc.WriteTo(resp, addr)
			}
		}
	}()
	return f
}

func serveTCPDNS(t testing.TB, ln net.Listener, f *fakeDNS, fn answerFn) {
	t.Cleanup(func() { ln.Close() })
	wf := wrapFn(f, fn)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				q, err := upstreamproxy.ReadTCPMessage(c)
				if err != nil {
					return
				}
				if resp := build(q, wf); resp != nil {
					upstreamproxy.WriteTCPMessage(c, resp)
				}
			}()
		}
	}()
}

func startTCP(t testing.TB, fn answerFn) *fakeDNS { return startTCPOn(t, "127.0.0.1:0", fn) }

func startTCPOn(t testing.TB, addr string, fn answerFn) *fakeDNS {
	f := &fakeDNS{}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	f.tcpAddr = ln.Addr().String()
	serveTCPDNS(t, ln, f, fn)
	return f
}

func startDoT(t testing.TB, cert tls.Certificate, fn answerFn) *fakeDNS {
	f := &fakeDNS{}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	f.tcpAddr = ln.Addr().String()
	serveTCPDNS(t, ln, f, fn)
	return f
}

func startDoH(t testing.TB, cert tls.Certificate, fn answerFn) (*fakeDNS, string) {
	f := &fakeDNS{}
	wf := wrapFn(f, fn)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/dns-message")
		w.Write(build(body, wf))
	}))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return f, srv.Listener.Addr().String()
}

func envFor(ca *testCA) Env {
	var roots *x509.CertPool
	if ca != nil {
		roots = ca.pool
	}
	return Env{
		Dial: func(r Route) DialFunc {
			if r == RouteDirect || r == RouteMesh {
				return func(ctx context.Context, n, a string) (net.Conn, error) {
					return (&net.Dialer{}).DialContext(ctx, n, a)
				}
			}
			return nil
		},
		Roots: roots,
	}
}

func ctx5(t testing.TB) context.Context {
	c, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return c
}

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time      { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) { c.mu.Lock(); c.t = c.t.Add(d); c.mu.Unlock() }

func simple(ip string, ttl uint32) answerFn {
	return func(n string, qt dnsmessage.Type) reply {
		if qt == dnsmessage.TypeA {
			return reply{ans: []dnsmessage.Resource{a(n, ip, ttl)}}
		}
		return reply{auth: []dnsmessage.Resource{soa("example.test", 300, 60)}}
	}
}

func splitHP(addr string) (string, string, error) { return net.SplitHostPort(addr) }
