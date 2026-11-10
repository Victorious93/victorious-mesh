// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"

	"tailscale.com/net/dns/publicdns"
)

// The mesh engine's own sockets ("system dials") are protected from the
// VPN, so they never reach the forced-proxy TUN path. Its DNS forwarder
// uses them for DNS over TCP (truncated answers / fallback) and for DNS
// over HTTPS to well-known public resolvers. While the forced proxy is on,
// EngineDNSGuard sends those through the proxy, and blocks them if the
// proxy is down (DNS is always fail-closed in forced mode).

// ErrEngineDNSBlocked means an engine DNS lookup was refused because it
// could not go through the forced proxy.
var ErrEngineDNSBlocked = errors.New("forced upstream: mesh engine DNS blocked (proxy unavailable)")

const (
	dnsOverTLSPort = 853
	httpsPort      = 443
)

var (
	meshV4 = netip.MustParsePrefix("100.64.0.0/10")
	meshV6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

// EngineDNSKind says why a system dial counts as DNS ("" = not DNS).
func EngineDNSKind(address string) string {
	host, portStr, err := net.SplitHostPort(address)
	if err != nil {
		return ""
	}
	port, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return ""
	}
	ip, ipErr := netip.ParseAddr(strings.Trim(host, "[]"))
	if ipErr == nil {
		ip = ip.Unmap()
		if meshV4.Contains(ip) || meshV6.Contains(ip) {
			return "" // mesh destinations stay on the mesh
		}
	}
	switch port {
	case dnsPort:
		return "dns-tcp"
	case dnsOverTLSPort:
		return "dns-tls"
	case httpsPort:
		if ipErr == nil {
			if _, _, ok := publicdns.DoHEndpointFromIP(ip); ok {
				return "doh"
			}
		}
	}
	return ""
}

// EngineDNSGuard wraps the mesh engine's system dialer.
type EngineDNSGuard struct {
	// Cfg returns the live forced-proxy config (normally [Get]).
	Cfg func() Config
	// ProxyDial reaches the proxy itself, outside the VPN.
	ProxyDial DialFunc
	// Direct is the engine's normal system dial (protected socket).
	Direct DialFunc
}

// Dial is a drop-in system dialer. Everything that is not engine DNS, and
// everything while the forced proxy is off, uses Direct unchanged.
func (g EngineDNSGuard) Dial(ctx context.Context, network, address string) (net.Conn, error) {
	cfg := Config{}
	if g.Cfg != nil {
		cfg = g.Cfg()
	}
	kind := EngineDNSKind(address)
	if !cfg.Enabled() || kind == "" {
		return g.Direct(ctx, network, address)
	}
	if !strings.HasPrefix(network, "tcp") {
		noteDNS("blocked")
		return nil, fmt.Errorf("%w: %s %s not supported through the proxy", ErrEngineDNSBlocked, network, kind)
	}
	cfg.FailClosed = true
	d := Dialer{Cfg: cfg, Dial: g.ProxyDial} // Direct nil: never the underlay
	c, err := d.DialTCP(ctx, address)
	if err != nil {
		noteDNS("blocked")
		return nil, fmt.Errorf("%w: %v", ErrEngineDNSBlocked, err)
	}
	noteDNS("tcp")
	return c, nil
}
