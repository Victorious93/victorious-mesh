package upstreamproxy

import (
	"net/netip"
	"testing"

	"tailscale.com/net/tsaddr"
)

func TestValidateAndDefaults(t *testing.T) {
	if err := Validate(Config{}); err != nil {
		t.Fatal(err)
	}
	if (Config{}).Enabled() {
		t.Fatal("zero config must be off")
	}
	if err := Validate(Config{Kind: KindHTTP, Host: "1.2.3.4", Port: 80}); err == nil {
		t.Fatal("privileged port")
	}
	if err := Validate(Config{Kind: KindHTTP, Host: "1.2.3.4", Port: 8080, Username: "u"}); err == nil {
		t.Fatal("one-sided auth")
	}
	ok := Config{Kind: KindSOCKS5, Host: "1.2.3.4", Port: 1080, Username: "u", Password: "p", FailClosed: true}
	if err := Validate(ok); err != nil {
		t.Fatal(err)
	}
	if !ok.Enabled() {
		t.Fatal("expected enabled")
	}
	if got := ok.ProxyAddr(); got != "1.2.3.4:1080" {
		t.Fatalf("addr %s", got)
	}
}

func TestParseKind(t *testing.T) {
	k, err := ParseKind("SOCKS5")
	if err != nil || k != KindSOCKS5 {
		t.Fatalf("%v %v", k, err)
	}
	if _, err := ParseKind("ftp"); err == nil {
		t.Fatal("expected error")
	}
}

func TestLoopPrefixes(t *testing.T) {
	p := LoopPrefixes("8.8.8.8")
	if len(p) != 1 || p[0].String() != "8.8.8.8/32" {
		t.Fatalf("%v", p)
	}
	if LoopPrefixes("proxy.example.com") != nil {
		t.Fatal("hostname is not a literal")
	}
	if LoopPrefixes("100.64.1.2") != nil {
		t.Fatal("mesh IP must stay in the VPN")
	}
	if !tsaddr.IsTailscaleIP(netip.MustParseAddr("100.64.1.2")) {
		t.Fatal("sanity")
	}
	if LoopPrefixes("127.0.0.1") != nil {
		t.Fatal("loopback")
	}
}

func TestSetGetClear(t *testing.T) {
	t.Cleanup(Clear)
	if Get().Enabled() {
		t.Fatal("default off")
	}
	if err := Set(Config{Kind: KindHTTP, Host: "10.0.0.1", Port: 8080}); err != nil {
		t.Fatal(err)
	}
	if !Get().Enabled() {
		t.Fatal("set")
	}
	Clear()
	if Get().Enabled() {
		t.Fatal("cleared")
	}
}
