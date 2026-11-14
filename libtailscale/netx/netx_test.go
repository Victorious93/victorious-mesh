// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package netx

import (
	"bufio"
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func plainDial(ctx context.Context, n, a string) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, n, a)
}

// torLike is a SOCKS5 server that records requests and tunnels to a web server.
type torLike struct {
	addr string
	mu   sync.Mutex
	seen []string
}

func startTorLike(t *testing.T, web string) *torLike {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	s := &torLike{addr: ln.Addr().String()}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				h := make([]byte, 2)
				if _, err := io.ReadFull(c, h); err != nil {
					return // reachability probe: connect and close
				}
				io.ReadFull(c, make([]byte, h[1]))
				c.Write([]byte{5, 0})
				rq := make([]byte, 4)
				io.ReadFull(c, rq)
				var host string
				switch rq[3] {
				case 1:
					b := make([]byte, 4)
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
				s.mu.Lock()
				s.seen = append(s.seen, host+":"+itoa(binary.BigEndian.Uint16(pb)))
				s.mu.Unlock()
				up, err := net.Dial("tcp", web)
				if err != nil {
					c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
					return
				}
				defer up.Close()
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				go io.Copy(up, c)
				io.Copy(c, up)
			}()
		}
	}()
	return s
}

func itoa(n uint16) string {
	return strings.TrimSpace(strings.Repeat(" ", 0) + string(appendInt(nil, int(n))))
}
func appendInt(b []byte, n int) []byte {
	if n >= 10 {
		b = appendInt(b, n/10)
	}
	return append(b, byte('0'+n%10))
}

func webAddr(t *testing.T) string {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "ok") }))
	t.Cleanup(srv.Close)
	return srv.Listener.Addr().String()
}

func TestSetForcedValidatesAndInvalidates(t *testing.T) {
	c := New()
	reconf := 0
	c.SetEnv(Env{ProtectedDial: plainDial, Reconfigure: func() { reconf++ }})
	t.Cleanup(c.ClearForced)
	for _, bad := range []string{
		`{`, `{"kind":"ftp","host":"h","port":1}`, `{"kind":"socks5","host":"","port":1080}`,
		`{"kind":"socks5","host":"h","port":0}`, `{"kind":"socks5","host":"h","port":65536}`,
		`{"kind":"http","host":"h","port":3,"backend":"tor"}`,    // Tor needs SOCKS5
		`{"kind":"socks5","host":"h","port":3,"serverName":"x"}`, // serverName is https-only
		`{"kind":"socks5","host":"h","port":3,"username":"u"}`,   // one-sided auth
		`{"kind":"socks5","host":"h","port":3,"outproxy":"x.i2p"}`,
	} {
		if err := c.SetForced(bad); err == nil {
			t.Errorf("accepted %s", bad)
		}
	}
	tor := `{"kind":"socks5","host":"127.0.0.1","port":9050,"backend":"tor"}`
	if err := c.SetForced(tor); err != nil {
		t.Fatal(err)
	}
	if c.BackendArmed() != "tor" || reconf != 1 {
		t.Fatalf("armed=%q reconf=%d", c.BackendArmed(), reconf)
	}
	// A mapping made while Tor was armed dies when the profile changes.
	ip, _ := c.vmap.Assign("tor", "x.onion")
	if err := c.SetForced(tor); err != nil || reconf != 1 {
		t.Fatalf("identical config must not reconfigure: %v %d", err, reconf)
	}
	if _, _, ok := c.vmap.Lookup(ip); !ok {
		t.Fatal("unchanged profile dropped mappings")
	}
	if err := c.SetForced(`{"kind":"socks5","host":"127.0.0.1","port":9150,"backend":"tor"}`); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := c.vmap.Lookup(ip); ok || reconf != 2 {
		t.Fatalf("changed profile kept stale mapping (reconf=%d)", reconf)
	}
	c.ClearForced()
	if c.BackendArmed() != "" {
		t.Fatal("still armed")
	}
}

func TestProxyTestJSONThroughTorLike(t *testing.T) {
	web := webAddr(t)
	tor := startTorLike(t, web)
	c := New()
	c.SetEnv(Env{ProtectedDial: plainDial})
	h, p, _ := net.SplitHostPort(tor.addr)
	prox := `{"kind":"socks5","host":"` + h + `","port":` + p + `,"backend":"tor"}`
	out := c.ProxyTest(prox, `{"host":"files.example.onion-free.test","port":80}`)
	var rep struct {
		Verdict string
		Steps   []struct {
			Stage string
			OK    bool
		}
		Error string
	}
	if err := json.Unmarshal([]byte(out), &rep); err != nil || rep.Verdict != "ok" {
		t.Fatalf("%s", out)
	}
	if len(tor.seen) != 1 || tor.seen[0] != "files.example.onion-free.test:80" {
		t.Fatalf("backend saw %v (hostname must arrive unresolved)", tor.seen)
	}
	if !strings.Contains(c.ProxyTest(`{"kind":"socks5","host":"h","port":0}`, `{"host":"x"}`), "error") {
		t.Fatal("bad profile not reported")
	}
}

func dnsServer(t *testing.T, count *atomic.Int64) string {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			count.Add(1)
			var p dnsmessage.Parser
			h, _ := p.Start(buf[:n])
			q, _ := p.Question()
			b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: h.ID, Response: true, RecursionAvailable: true})
			b.StartQuestions()
			b.Question(q)
			b.StartAnswers()
			b.AResource(dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60}, dnsmessage.AResource{A: [4]byte{192, 0, 2, 1}})
			out, _ := b.Finish()
			pc.WriteTo(out, addr)
		}
	}()
	return pc.LocalAddr().String()
}

func TestDNSQueryJSONGuardsHistoryAndCache(t *testing.T) {
	var hits atomic.Int64
	srv := dnsServer(t, &hits)
	c := New()
	c.SetEnv(Env{ProtectedDial: plainDial})
	prof := `{"id":"p1","name":"test","transport":"udp","server":"` + srv + `","route":"direct"}`
	q := func(name, typ string) map[string]any {
		var m map[string]any
		if err := json.Unmarshal([]byte(c.DNSQuery(prof, name, typ, "")), &m); err != nil {
			t.Fatal(err)
		}
		return m
	}
	if r := q("example.test", "A"); r["rcode"] != "NOERROR" || r["cache"] != "miss" {
		t.Fatalf("%v", r)
	}
	if r := q("example.test", "A"); r["cache"] != "hit" || hits.Load() != 1 {
		t.Fatalf("%v hits=%d", r, hits.Load())
	}
	c.DNSClearCache()
	if r := q("example.test", "A"); r["cache"] != "miss" || hits.Load() != 2 {
		t.Fatalf("%v", r)
	}
	for _, n := range []string{"abc.onion", "forum.i2p", "printer.local"} {
		if r := q(n, "A"); r["error"] == nil || r["error"] == "" {
			t.Fatalf("%s reached a public resolver: %v", n, r)
		}
	}
	if hits.Load() != 2 {
		t.Fatalf("reserved names hit the network: %d", hits.Load())
	}
	// History is off until enabled, then bounded and clearable.
	if c.DNSHistoryJSON() != "[]" && c.DNSHistoryJSON() != "null" {
		t.Fatalf("history recorded while disabled: %s", c.DNSHistoryJSON())
	}
	c.DNSHistoryEnable(true)
	q("hist.test", "A")
	if !strings.Contains(c.DNSHistoryJSON(), "hist.test") {
		t.Fatal("history empty")
	}
	c.DNSHistoryEnable(false)
	if strings.Contains(c.DNSHistoryJSON(), "hist.test") {
		t.Fatal("disabling must erase history")
	}
	// Bad inputs.
	if !strings.Contains(c.DNSQuery(`{"transport":"carrier-pigeon","route":"direct"}`, "a.test", "A", ""), "unknown transport") {
		t.Fatal("transport")
	}
	if !strings.Contains(c.DNSQuery(`{"transport":"udp","server":"1.1.1.1","route":"vpn"}`, "a.test", "A", ""), "unknown route") {
		t.Fatal("route")
	}
	if !strings.Contains(c.DNSQuery(prof, "a.test", "ANY", ""), "unsupported record type") {
		t.Fatal("type")
	}
	// Local policy cannot map an overlay name.
	if err := c.DNSSetLocalPolicy(`{"hosts":{"x.onion":["1.2.3.4"]}}`); err == nil {
		t.Fatal("mapped an onion name to an IP")
	}
	if err := c.DNSSetLocalPolicy(`{"hosts":{"nas.test":["192.168.1.2"]},"block":["*.ads.test"]}`); err != nil {
		t.Fatal(err)
	}
	if r := q("nas.test", "A"); r["cache"] != "local" || hits.Load() > 3 {
		t.Fatalf("%v", r)
	}
	// Reverse lookup helper builds the arpa name.
	if !strings.Contains(c.DNSReverse(prof, "192.0.2.10"), "10.2.0.192.in-addr.arpa") {
		t.Fatal("reverse")
	}
}

func TestProxyRouteDNSNeedsForcedProfile(t *testing.T) {
	c := New()
	c.SetEnv(Env{ProtectedDial: plainDial})
	out := c.DNSQuery(`{"name":"viaproxy","transport":"tcp","server":"1.1.1.1:53","route":"proxy"}`, "a.test", "A", "")
	if !strings.Contains(out, "not falling back") {
		t.Fatalf("proxy route must fail closed when no proxy is armed: %s", out)
	}
	out = c.DNSQuery(`{"name":"viator","transport":"tcp","server":"1.1.1.1:53","route":"tor"}`, "a.test", "A", "")
	if !strings.Contains(out, "not falling back") {
		t.Fatalf("%s", out)
	}
}

func TestLeakCheckJSONIsInconclusiveWithoutObserver(t *testing.T) {
	c := New()
	c.SetEnv(Env{ProtectedDial: plainDial})
	out := c.LeakCheck(`{"config":{"vpnActive":true,"killSwitch":true,"dnsMode":"mesh"}}`)
	var r struct {
		Overall string
		Summary string
	}
	json.Unmarshal([]byte(out), &r)
	if r.Overall != "inconclusive" || !strings.Contains(r.Summary, "not measured") {
		t.Fatalf("%s", out)
	}
}

var _ = bufio.NewReader
var _ = netip.Addr{}

func TestParseAndRedactURI(t *testing.T) {
	c := New()
	out := c.ParseURI("https://u:p%40ss@[2001:db8::1]:443")
	var m map[string]any
	if err := json.Unmarshal([]byte(out), &m); err != nil || m["kind"] != "https" || m["host"] != "2001:db8::1" || m["password"] != "p@ss" || m["port"].(float64) != 443 {
		t.Fatalf("%s", out)
	}
	for _, bad := range []string{"https://host", "ftp://h:1", "socks5://h:0", "http://h:70000"} {
		if !strings.Contains(c.ParseURI(bad), "error") {
			t.Errorf("%s accepted", bad)
		}
	}
	red := c.RedactURI(`{"kind":"socks5","host":"h.example","port":1080,"username":"alice","password":"hunter2"}`)
	if strings.Contains(red, "hunter2") || strings.Contains(red, "alice") || !strings.Contains(red, "h.example:1080") {
		t.Fatalf("%q", red)
	}
}
