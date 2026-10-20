// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package wgquick

import (
	"net/netip"
	"strings"
	"testing"
)

const (
	tPriv = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
	tPub1 = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
	tPub2 = "TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0="
	tPSK  = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="
)

func fakeResolve(s string) (netip.AddrPort, error) {
	if ap, err := netip.ParseAddrPort(s); err == nil {
		return ap, nil
	}
	return netip.MustParseAddrPort("203.0.113.7:51820"), nil
}

func TestParseValid(t *testing.T) {
	c, err := Parse(`
# comment
[Interface]
PrivateKey = ` + tPriv + `
Address = 10.0.0.2/32, fd00::2/128
DNS = 1.1.1.1, corp.example
MTU = 1420
PostUp = iptables -A ...

[Peer]
PublicKey = ` + tPub1 + `
PresharedKey = ` + tPSK + `
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
`)
	if err != nil {
		t.Fatal(err)
	}
	if c.MTU != 1420 || len(c.Addresses) != 2 || len(c.DNSServers) != 1 || c.SearchDomains[0] != "corp.example" {
		t.Fatalf("bad cfg: %+v", c)
	}
	u, err := c.UAPI(fakeResolve)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"private_key=c809f3e5", "endpoint=203.0.113.7:51820", "persistent_keepalive_interval=25", "allowed_ip=0.0.0.0/0", "allowed_ip=::/0", "preshared_key="} {
		if !strings.Contains(u, want) {
			t.Errorf("uapi missing %q", want)
		}
	}
	if s := c.SummaryJSON(); strings.Contains(s, "c809f3e5") || strings.Contains(s, tPriv) || strings.Contains(s, tPSK) {
		t.Errorf("summary leaks secrets: %s", s)
	}
}

func TestParseMultiplePeersIPv6(t *testing.T) {
	c, err := Parse(`[Interface]
PrivateKey = ` + tPriv + `
Address = fd00:1::5
[Peer]
PublicKey = ` + tPub1 + `
AllowedIPs = 10.1.0.0/16
AllowedIPs = 10.2.0.0/16
Endpoint = [2001:db8::1]:51820
[Peer]
PublicKey = ` + tPub2 + `
AllowedIPs = fd00:2::/64, 10.1.0.0/16
`)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Peers) != 2 || c.MTU != DefaultMTU {
		t.Fatalf("peers=%d mtu=%d", len(c.Peers), c.MTU)
	}
	if c.Addresses[0].String() != "fd00:1::5/128" {
		t.Errorf("addr %v", c.Addresses[0])
	}
	if r := c.Routes(); len(r) != 3 {
		t.Errorf("routes not deduplicated: %v", r)
	}
	u, _ := c.UAPI(fakeResolve)
	if !strings.Contains(u, "endpoint=[2001:db8::1]:51820") || strings.Count(u, "public_key=") != 2 {
		t.Errorf("uapi: %s", u)
	}
}

func TestParseInvalid(t *testing.T) {
	iface := "[Interface]\nPrivateKey = " + tPriv + "\nAddress = 10.0.0.2/32\n"
	peer := "[Peer]\nPublicKey = " + tPub1 + "\nAllowedIPs = 0.0.0.0/0\n"
	cases := map[string]string{
		"empty":           "",
		"missing priv":    "[Interface]\nAddress = 10.0.0.2/32\n" + peer,
		"missing address": "[Interface]\nPrivateKey = " + tPriv + "\n" + peer,
		"no peer":         iface,
		"no allowedips":   iface + "[Peer]\nPublicKey = " + tPub1 + "\n",
		"peer no pubkey":  iface + "[Peer]\nAllowedIPs = 0.0.0.0/0\n",
		"bad key":         "[Interface]\nPrivateKey = notakey\nAddress = 10.0.0.2/32\n" + peer,
		"bad address":     "[Interface]\nPrivateKey = " + tPriv + "\nAddress = 10.0.0.300/32\n" + peer,
		"bad endpoint":    iface + peer + "Endpoint = noport\n",
		"bad mtu":         "[Interface]\nPrivateKey = " + tPriv + "\nAddress = 10.0.0.2/32\nMTU = 12\n" + peer,
		"unknown section": iface + "[Bogus]\n" + peer,
		"unknown key":     iface + "Frobnicate = 1\n" + peer,
		"no section":      "PrivateKey = " + tPriv + "\n",
		"no equals":       iface + "garbage\n" + peer,
	}
	for name, in := range cases {
		if _, err := Parse(in); err == nil {
			t.Errorf("%s: expected error", name)
		} else if strings.Contains(err.Error(), tPriv) {
			t.Errorf("%s: error leaks key", name)
		}
	}
}

func TestParseListenPortHexKeyAndComments(t *testing.T) {
	hexPriv := "c809f3e5317e9575c9b5ed78b638b7ce530dabe85ddab614220241801ddf0669"
	c, err := Parse(`[Interface]
PrivateKey = ` + hexPriv + ` # local
Address = 10.0.0.2/32 # tunnel addr
DNS = 2001:4860:4860::8888
ListenPort = 51820
[Peer]
PublicKey = ` + tPub1 + `
AllowedIPs = 10.8.0.0/24
Endpoint = 203.0.113.9:51820 # pop
`)
	if err != nil {
		t.Fatal(err)
	}
	if c.ListenPort != 51820 || len(c.DNSServers) != 1 || !c.DNSServers[0].Is6() {
		t.Fatalf("listen=%d dns=%v", c.ListenPort, c.DNSServers)
	}
	u, err := c.UAPI(fakeResolve)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(u, "listen_port=51820") || !strings.Contains(u, "private_key=c809f3e5") {
		t.Errorf("uapi: %s", u)
	}
}

func TestDNSForVPNFallbackOnFullTunnel(t *testing.T) {
	full, err := Parse(`[Interface]
PrivateKey = ` + tPriv + `
Address = 10.0.0.2/32
[Peer]
PublicKey = ` + tPub1 + `
AllowedIPs = 0.0.0.0/0, ::/0
`)
	if err != nil {
		t.Fatal(err)
	}
	dns := full.DNSForVPN()
	if len(dns) != 3 {
		t.Fatalf("fallback dns: %v", dns)
	}
	split, err := Parse(`[Interface]
PrivateKey = ` + tPriv + `
Address = 10.0.0.2/32
[Peer]
PublicKey = ` + tPub1 + `
AllowedIPs = 10.8.0.0/24
`)
	if err != nil {
		t.Fatal(err)
	}
	if got := split.DNSForVPN(); len(got) != 0 {
		t.Fatalf("split-tunnel should not invent DNS: %v", got)
	}
	v4, v6 := full.HasDefaultRoute()
	if !v4 || !v6 {
		t.Fatalf("default routes v4=%v v6=%v", v4, v6)
	}
}
