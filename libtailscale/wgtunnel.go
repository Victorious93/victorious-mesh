// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

// WireGuard tunnel mode: runs a plain wireguard-go device on the TUN fd
// established by the app's single IPNService (VpnService). Mesh mode and
// WireGuard mode are mutually exclusive; the Kotlin side tears the mesh
// tunnel down before calling StartWireGuard.

import (
	"errors"
	"fmt"
	"log"
	"net/netip"
	"sync"

	"github.com/tailscale/tailscale-android/libtailscale/wgquick"
	"github.com/tailscale/wireguard-go/conn"
	"github.com/tailscale/wireguard-go/device"
	"github.com/tailscale/wireguard-go/tun"
)

var wgState struct {
	mu  sync.Mutex
	dev *device.Device
}

type socketFdPeeker interface {
	PeekLookAtSocketFd4() (int, error)
	PeekLookAtSocketFd6() (int, error)
}

// ValidateWireGuardConfig parses a wg-quick config and returns a JSON
// summary without any key material.
func ValidateWireGuardConfig(config string) (string, error) {
	cfg, err := wgquick.Parse(config)
	if err != nil {
		return "", err
	}
	return cfg.SummaryJSON(), nil
}

// WireGuardRunning reports whether a WireGuard device is active.
func WireGuardRunning() bool {
	wgState.mu.Lock()
	defer wgState.mu.Unlock()
	return wgState.dev != nil
}

// StopWireGuard tears down the WireGuard device (and thus its TUN fd).
func StopWireGuard() {
	wgState.mu.Lock()
	defer wgState.mu.Unlock()
	stopWireGuardLocked()
}

func stopWireGuardLocked() {
	if wgState.dev != nil {
		wgState.dev.Close() // closes the tun device and bind
		wgState.dev = nil
		log.Printf("wireguard: stopped")
	}
}

// StartWireGuard establishes a VPN via service.NewBuilder using the given
// wg-quick config and runs wireguard-go on it.
func StartWireGuard(service IPNService, config string) (err error) {
	if service == nil {
		return errors.New("no VPN service")
	}
	cfg, err := wgquick.Parse(config)
	if err != nil {
		return err
	}
	wgState.mu.Lock()
	defer wgState.mu.Unlock()
	stopWireGuardLocked()

	// Resolve endpoints before the tunnel captures traffic, then reuse
	// the results for UAPI and for ExcludeRoute (so handshake packets
	// are not sent into 0.0.0.0/0 on API 33+ even if protect() fails).
	resolved := make(map[string]netip.AddrPort)
	for _, p := range cfg.Peers {
		if p.Endpoint == "" {
			continue
		}
		ap, err := wgquick.ResolveEndpoint(p.Endpoint)
		if err != nil {
			return fmt.Errorf("resolving endpoint %q: %w", p.Endpoint, err)
		}
		resolved[p.Endpoint] = ap
	}
	uapi, err := cfg.UAPI(func(s string) (netip.AddrPort, error) {
		if ap, ok := resolved[s]; ok {
			return ap, nil
		}
		return wgquick.ResolveEndpoint(s)
	})
	if err != nil {
		return err
	}

	b := service.NewBuilder()
	if err := b.SetMTU(int32(cfg.MTU)); err != nil {
		return err
	}
	for _, a := range cfg.Addresses {
		if err := b.AddAddress(a.Addr().String(), int32(a.Bits())); err != nil {
			return err
		}
	}
	for _, d := range cfg.DNSForVPN() {
		if err := b.AddDNSServer(d.String()); err != nil {
			return err
		}
	}
	for _, d := range cfg.SearchDomains {
		if err := b.AddSearchDomain(d); err != nil {
			return err
		}
	}
	for _, r := range cfg.Routes() {
		if err := b.AddRoute(r.Addr().String(), int32(r.Bits())); err != nil {
			return err
		}
	}
	for _, ap := range resolved {
		if err := b.ExcludeRoute(ap.Addr().String(), int32(ap.Addr().BitLen())); err != nil {
			log.Printf("wireguard: excludeRoute(%s) failed: %v", ap.Addr(), err)
		}
	}
	pfd, err := b.Establish()
	if err != nil {
		return fmt.Errorf("VpnService.Builder.establish: %v", err)
	}
	if pfd == nil {
		return errVPNNotPrepared
	}
	fd, err := pfd.Detach()
	if err != nil {
		return fmt.Errorf("detachFd: %v", err)
	}
	tunDev, _, err := tun.CreateUnmonitoredTUNFromFD(int(fd))
	if err != nil {
		closeFD(fd)
		return err
	}
	bind := conn.NewStdNetBind()
	logger := &device.Logger{
		Verbosef: device.DiscardLogf,
		Errorf:   func(f string, a ...any) { log.Printf("wireguard: "+f, a...) },
	}
	dev := device.NewDevice(tunDev, bind, logger)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return errors.New("wireguard: applying config failed") // never echo uapi (contains keys)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return fmt.Errorf("wireguard: up: %v", err)
	}
	// Sockets exist only after Up (Bind.Open). Protect immediately so
	// handshake packets are not captured by 0.0.0.0/0.
	protectSockets(service, bind)
	wgState.dev = dev
	service.UpdateVpnStatus(true)
	log.Printf("wireguard: up with %d peer(s), mtu %d", len(cfg.Peers), cfg.MTU)
	return nil
}

func protectSockets(service IPNService, bind conn.Bind) {
	p, ok := bind.(socketFdPeeker)
	if !ok {
		return
	}
	// PeekLookAtSocketFd* on StdNetBind dereferences ipv4/ipv6 with no
	// nil check; recover so a missing family cannot tear the tunnel down.
	peek := func(name string, fn func() (int, error)) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("wireguard: protect(%s): %v", name, rec)
			}
		}()
		fd, err := fn()
		if err != nil {
			return
		}
		if !service.Protect(int32(fd)) {
			log.Printf("wireguard: protect(%s) failed", name)
		}
	}
	peek("v4", p.PeekLookAtSocketFd4)
	peek("v6", p.PeekLookAtSocketFd6)
}
