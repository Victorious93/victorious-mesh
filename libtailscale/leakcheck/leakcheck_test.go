// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package leakcheck

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func ctx(t testing.TB) context.Context {
	c, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return c
}

// lookupVia sends a real DNS query over UDP to addr (the "path under test").
func lookupVia(addr string) Lookup {
	return func(ctx context.Context, name string) error {
		n, _ := dnsmessage.NewName(name + ".")
		b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, RecursionDesired: true})
		b.StartQuestions()
		b.Question(dnsmessage.Question{Name: n, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
		q, _ := b.Finish()
		c, err := (&net.Dialer{}).DialContext(ctx, "udp", addr)
		if err != nil {
			return err
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(2 * time.Second))
		c.Write(q)
		buf := make([]byte, 512)
		_, err = c.Read(buf)
		return err
	}
}

func endpoint(t *testing.T, fx *FixtureServer) *Observer {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"resolvers": fx.Resolvers(r.URL.Query().Get("token"))})
	}))
	t.Cleanup(srv.Close)
	return &Observer{Zone: fx.Zone, Endpoint: srv.URL, HTTP: srv.Client()}
}

func TestObservedDNSPassFailInconclusive(t *testing.T) {
	fx, err := NewFixtureServer("leak.test")
	if err != nil {
		t.Fatal(err)
	}
	defer fx.Close()
	obs := endpoint(t, fx)

	// The query arrives from 127.0.0.1 (the only source the fixture can see).
	local := []netip.Addr{netip.MustParseAddr("127.0.0.1")}
	f := ObserveDNS(ctx(t), obs, lookupVia(fx.Addr()), local)
	if f.Verdict != Pass || f.Evidence != EvidenceVerified {
		t.Fatalf("expected pass: %+v", f)
	}
	// The same observation against a different expected resolver is a leak.
	f = ObserveDNS(ctx(t), obs, lookupVia(fx.Addr()), []netip.Addr{netip.MustParseAddr("203.0.113.5")})
	if f.Verdict != Fail || !strings.Contains(f.Detail, "127.0.0.1") {
		t.Fatalf("expected fail: %+v", f)
	}
	// Unknown expectation: seen, but nothing to compare to.
	if f = ObserveDNS(ctx(t), obs, lookupVia(fx.Addr()), nil); f.Verdict != Inconclusive {
		t.Fatalf("%+v", f)
	}
	// The lookup never reaches the service (blocked/cached): cannot call it a pass.
	blocked := func(context.Context, string) error { return net.ErrClosed }
	f = ObserveDNS(ctx(t), obs, blocked, local)
	if f.Verdict != Inconclusive || !strings.Contains(f.Detail, "does not prove") {
		t.Fatalf("a query that was never seen must be inconclusive: %+v", f)
	}
	// No observer configured / insecure endpoint: inconclusive, configuration evidence only.
	for _, o := range []*Observer{nil, {}, {Zone: "z", Endpoint: "http://x/"}} {
		f = ObserveDNS(ctx(t), o, lookupVia(fx.Addr()), local)
		if f.Verdict != Inconclusive || f.Evidence == EvidenceVerified {
			t.Fatalf("%+v -> %+v", o, f)
		}
	}
	// Endpoint down.
	dead := &Observer{Zone: fx.Zone, Endpoint: "https://127.0.0.1:1/x", HTTP: &http.Client{Timeout: time.Second}}
	if f = ObserveDNS(ctx(t), dead, lookupVia(fx.Addr()), local); f.Verdict != Inconclusive {
		t.Fatalf("%+v", f)
	}
}

func TestConfigInspectionNeverVerifies(t *testing.T) {
	fs := InspectConfig(ConfigInputs{VPNActive: true, KillSwitch: true, ForcedProxy: true, ProxyFailClosed: true, DNSMode: "forced-proxy", PerNetworkDNSIgnored: true, TorRequired: true})
	for _, f := range fs {
		if f.Evidence != EvidenceConfig {
			t.Fatalf("config inspection claimed %s evidence: %+v", f.Evidence, f)
		}
		if f.ID == "dns-config" && f.Verdict != Inconclusive {
			t.Fatalf("%+v", f)
		}
	}
	got := map[string]Finding{}
	for _, f := range fs {
		got[f.ID] = f
	}
	if got["pndns"].Verdict != Warn || !strings.Contains(got["pndns"].Detail, "NOT being applied") {
		t.Fatalf("ignored per-network DNS must be visible: %+v", got["pndns"])
	}
	if got["tor"].Verdict != Warn {
		t.Fatalf("%+v", got["tor"])
	}
	if v, msg := Overall(fs); v != Inconclusive || !strings.Contains(msg, "not a clean bill") {
		t.Fatalf("overall %v %q: config alone must not read as a pass", v, msg)
	}
	// Overall is Pass only with an observed pass and no warnings.
	clean := InspectConfig(ConfigInputs{VPNActive: true, KillSwitch: true, DNSMode: "mesh"})
	clean = append(clean, Finding{ID: "dns-observed", Verdict: Pass, Evidence: EvidenceVerified})
	// the "scope" finding is a standing warning, so the best overall is Warn.
	if v, _ := Overall(clean); v != Warn {
		t.Fatalf("overall %v", v)
	}
	fail := append(clean, Finding{ID: "dns-observed", Verdict: Fail})
	if v, _ := Overall(fail); v != Fail {
		t.Fatalf("overall %v", v)
	}
	if f := InspectConfig(ConfigInputs{}); f[0].Verdict != Fail {
		t.Fatalf("VPN down must fail: %+v", f[0])
	}
}
