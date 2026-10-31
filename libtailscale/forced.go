// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"log"
	"net"
	"sync"
	"time"

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
	startLoopWatch()
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
	upstreamproxy.NoteLoopPrefixes(ex)
	next.LocalRoutes = append(append([]netip.Prefix(nil), rcfg.LocalRoutes...), ex...)
	return &next
}

// vpnServiceMu guards vpnService.service for readers outside the
// backend loop (the forced-proxy dialer runs on gVisor goroutines).
var vpnServiceMu sync.RWMutex

func setVPNService(s IPNService) {
	vpnServiceMu.Lock()
	vpnService.service = s
	vpnServiceMu.Unlock()
}

func currentVPNService() IPNService {
	vpnServiceMu.RLock()
	defer vpnServiceMu.RUnlock()
	return vpnService.service
}

// protectedDial reaches the proxy outside the VPN. If the socket cannot
// be protected the dial fails instead of looping into the TUN.
func protectedDial(ctx context.Context, network, address string) (net.Conn, error) {
	var protect func(int32) bool
	if svc := currentVPNService(); svc != nil {
		protect = func(fd int32) bool {
			if svc.Protect(fd) {
				return true
			}
			log.Printf("forced upstream: protect(%d) returned false", fd)
			return false
		}
	}
	d := net.Dialer{Control: upstreamproxy.ProtectControl(protect)}
	return d.DialContext(ctx, network, address)
}

var loopWatchOnce sync.Once

// startLoopWatch re-resolves a proxy hostname every few minutes and
// after a failed proxy dial; if its addresses changed, the VPN routes
// are rebuilt so the new address stays off the tunnel.
func startLoopWatch() {
	loopWatchOnce.Do(func() {
		go func() {
			t := time.NewTicker(3 * time.Minute)
			defer t.Stop()
			for {
				select {
				case <-t.C:
				case <-upstreamproxy.ProxyDialFailures():
					time.Sleep(2 * time.Second) // coalesce bursts
				}
				cfg := upstreamproxy.Get()
				if cfg.Enabled() && upstreamproxy.LoopPrefixesStale(cfg.Host) {
					log.Printf("forced upstream: proxy address changed; reconfiguring")
					reconfigureForcedVPN()
				}
			}
		}()
	})
}
