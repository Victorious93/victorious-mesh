// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"log"
	"net"
	"syscall"

	"net/netip"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
	"tailscale.com/wgengine/router"
)

// SetForcedUpstream turns on the TUN→SOCKS5/HTTP-CONNECT path.
// kind is "http", "socks5", or empty/none to disable. Off by default.
// failClosed blocks new flows when the proxy is down (no direct fallback).
func SetForcedUpstream(kind, host string, port int32, username, password string, failClosed bool) error {
	k, err := upstreamproxy.ParseKind(kind)
	if err != nil {
		return err
	}
	cfg := upstreamproxy.Config{
		Kind:       k,
		Host:       host,
		Port:       port,
		Username:   username,
		Password:   password,
		FailClosed: failClosed,
	}
	if err := upstreamproxy.Set(cfg); err != nil {
		return err
	}
	reconfigureForcedVPN()
	return nil
}

// ClearForcedUpstream turns the forced path off.
func ClearForcedUpstream() {
	upstreamproxy.Clear()
	reconfigureForcedVPN()
}

// ForcedUpstreamEnabled reports whether the Go path is armed.
func ForcedUpstreamEnabled() bool {
	return upstreamproxy.Get().Enabled()
}

// ForcedDNSState is "off", "pending", "udp", "tcp", or "blocked".
func ForcedDNSState() string {
	return upstreamproxy.DNSState()
}

// ForcedIPv6State is "off", "unknown", "via_proxy", or "blocked".
func ForcedIPv6State() string {
	return upstreamproxy.IPv6State()
}

var forcedReconfigure func() error

func reconfigureForcedVPN() {
	if fn := forcedReconfigure; fn != nil {
		if err := fn(); err != nil {
			log.Printf("forced upstream: reconfigure: %v", err)
		}
	}
}

func applyForcedRoutes(rcfg *router.Config) *router.Config {
	if rcfg == nil {
		return nil
	}
	cfg := upstreamproxy.Get()
	if !cfg.Enabled() {
		return rcfg
	}
	next := *rcfg
	next.Routes = append(append([]netip.Prefix(nil), rcfg.Routes...), upstreamproxy.DefaultRoutes()...)
	ex := append(upstreamproxy.LoopPrefixes(cfg.Host), upstreamproxy.ResolveLoopPrefixes(cfg.Host)...)
	next.LocalRoutes = append(append([]netip.Prefix(nil), rcfg.LocalRoutes...), ex...)
	return &next
}

func protectedDial(ctx context.Context, network, address string) (net.Conn, error) {
	d := net.Dialer{
		Control: func(_, _ string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				if vpnService.service != nil {
					if !vpnService.service.Protect(int32(fd)) {
						log.Printf("forced upstream: protect(%d) returned false", fd)
					}
				}
			})
		},
	}
	return d.DialContext(ctx, network, address)
}
