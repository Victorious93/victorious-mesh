// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"net/netip"
	"strings"
	"sync"

	"github.com/tailscale/tailscale-android/libtailscale/netx"
)

// Thin gomobile wrappers over package netx: Tor / I2P / HTTPS proxy
// profiles, the reserved-name DNS guard, the DNS tools and the leak
// checker. Every function takes and returns simple types (JSON strings).

var (
	sysDNSMu sync.Mutex
	sysDNS   []netip.AddrPort
)

func init() {
	netx.Shared.SetEnv(netx.Env{
		ProtectedDial: protectedDial,
		Reconfigure:   reconfigureForcedVPN,
		SystemServers: func() []netip.AddrPort {
			sysDNSMu.Lock()
			defer sysDNSMu.Unlock()
			return append([]netip.AddrPort(nil), sysDNS...)
		},
	})
}

// SetSystemDNSServers tells the DNS tools which resolvers the current
// underlying network handed out (comma or space separated IPs).
func SetSystemDNSServers(servers string) {
	var out []netip.AddrPort
	for _, f := range strings.FieldsFunc(servers, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' }) {
		if ip, err := netip.ParseAddr(strings.TrimSpace(f)); err == nil {
			out = append(out, netip.AddrPortFrom(ip.Unmap(), 53))
		}
	}
	sysDNSMu.Lock()
	sysDNS = out
	sysDNSMu.Unlock()
}

// SetReservedNameGuard turns the always-on guard on or off: while on,
// DNS queries for .onion / .i2p names are answered locally (a virtual
// address when a Tor / I2P backend is armed, REFUSED otherwise) and never
// reach any resolver, even with no proxy configured.
func SetReservedNameGuard(on bool) {
	if netx.Shared.GuardEnabled() == on {
		return
	}
	netx.Shared.SetGuard(on)
	reconfigureForcedVPN() // the TUN wrapper is built per configuration
}

// InvalidateOverlayState drops virtual-address mappings, I2P sessions and
// DNS caches (call on any stricter policy change or network handover).
func InvalidateOverlayState() { netx.Shared.InvalidateAll() }

// OverlayBackend is "tor", "i2p" or "" for the device-wide profile.
func OverlayBackend() string { return netx.Shared.BackendArmed() }

// OverlayMappingCount is the number of live name↔virtual-address entries.
func OverlayMappingCount() int { return netx.Shared.MappingCount() }

// ParseProxyURI parses http / https / socks5 / socks5h / sam URIs into JSON.
func ParseProxyURI(raw string) string { return netx.Shared.ParseURI(raw) }

// RedactProxyURI shows a profile as a URI with user name and password hidden.
func RedactProxyURI(proxyJSON string) string { return netx.Shared.RedactURI(proxyJSON) }

func ProxyTest(proxyJSON, targetJSON string) string {
	return netx.Shared.ProxyTest(proxyJSON, targetJSON)
}
func I2PLookup(proxyJSON, name string) string { return netx.Shared.I2PLookup(proxyJSON, name) }

func DNSQuery(profileJSON, name, qtype, optsJSON string) string {
	return netx.Shared.DNSQuery(profileJSON, name, qtype, optsJSON)
}
func DNSReverse(profileJSON, ip string) string { return netx.Shared.DNSReverse(profileJSON, ip) }
func DNSCompare(profilesJSON, name, qtype string) string {
	return netx.Shared.DNSCompare(profilesJSON, name, qtype)
}
func DNSLatency(profileJSON, name, qtype string, samples int) string {
	return netx.Shared.DNSLatency(profileJSON, name, qtype, samples)
}
func DNSSetLocalPolicy(js string) error { return netx.Shared.DNSSetLocalPolicy(js) }
func DNSClearCache()                    { netx.Shared.DNSClearCache() }
func DNSCacheSize() int                 { return netx.Shared.DNSCacheSize() }
func DNSHistoryEnable(on bool)          { netx.Shared.DNSHistoryEnable(on) }
func DNSHistoryClear()                  { netx.Shared.DNSHistoryClear() }
func DNSHistoryJSON() string            { return netx.Shared.DNSHistoryJSON() }
func LeakCheck(reqJSON string) string   { return netx.Shared.LeakCheck(reqJSON) }
