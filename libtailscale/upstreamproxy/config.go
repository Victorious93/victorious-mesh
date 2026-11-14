// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package upstreamproxy is the forced TUN→SOCKS5/HTTP-CONNECT path.
// Off by default. JVM-adjacent Go tests; no Android imports.
package upstreamproxy

import (
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"

	"tailscale.com/net/tsaddr"
)

const (
	// MinPort is the lowest REMOTE proxy port. Remote endpoints may use
	// any TCP port; only locally bound listeners (meshproxy) keep the
	// unprivileged 1024 floor.
	MinPort = 1
	MaxPort = 65535
)

type Kind string

const (
	KindNone   Kind = ""
	KindHTTP   Kind = "http"   // HTTP CONNECT over plaintext TCP to the proxy
	KindHTTPS  Kind = "https"  // HTTP CONNECT inside a verified TLS session to the proxy
	KindSOCKS5 Kind = "socks5" // SOCKS5 (CONNECT, UDP ASSOCIATE)
	KindSAM    Kind = "sam"    // I2P SAM v3 streaming bridge (TCP only)
)

// Backend names a naming-aware service behind the proxy endpoint. It
// changes how reserved names (.onion, .i2p) are treated; "" is an
// ordinary proxy that never sees reserved names.
type Backend string

const (
	BackendNone Backend = ""
	// BackendTor: a Tor SOCKS5 endpoint. Hostnames are passed to Tor
	// unresolved; .onion names are only ever sent to this backend.
	BackendTor Backend = "tor"
	// BackendI2PSAM: an I2P router's SAM v3 bridge (Host:Port is SAM).
	BackendI2PSAM Backend = "i2p-sam"
	// BackendI2PHTTP: an I2P router's HTTP proxy (usually 127.0.0.1:4444).
	BackendI2PHTTP Backend = "i2p-http"
)

func (b Backend) valid() bool {
	switch b {
	case BackendNone, BackendTor, BackendI2PSAM, BackendI2PHTTP:
		return true
	}
	return false
}

// Config is the user-chosen upstream. Zero value is off.
type Config struct {
	Kind       Kind
	Host       string
	Port       int32
	Username   string
	Password   string
	FailClosed bool
	// ServerName is the TLS name checked against the proxy certificate
	// (KindHTTPS only). Empty means Host. It is never used to skip
	// verification: there is no "insecure" option.
	ServerName string
	// Backend marks a Tor/I2P gateway. See [Backend].
	Backend Backend
	// Outproxy is the explicit, optional clearnet exit for I2P
	// backends. For i2p-sam it is the I2P name of an outproxy that
	// speaks HTTP CONNECT; for i2p-http any non-empty value asserts
	// that the router itself has an outproxy configured. Empty means
	// clearnet destinations are refused (no direct fallback).
	Outproxy string
}

func (c Config) Enabled() bool {
	switch c.Kind {
	case KindHTTP, KindHTTPS, KindSOCKS5, KindSAM:
	default:
		return false
	}
	return strings.TrimSpace(c.Host) != "" && ValidPort(c.Port)
}

// TLSName is the name the proxy certificate must match.
func (c Config) TLSName() string {
	if n := strings.TrimSpace(c.ServerName); n != "" {
		return n
	}
	return strings.Trim(strings.TrimSpace(c.Host), "[]")
}

func (c Config) ProxyAddr() string {
	return net.JoinHostPort(strings.Trim(strings.TrimSpace(c.Host), "[]"), fmt.Sprintf("%d", c.Port))
}

func ValidPort(port int32) bool { return port >= MinPort && port <= MaxPort }

func ValidateAuth(username, password string) error {
	if (username == "") != (password == "") {
		return fmt.Errorf("username and password must both be set or both empty")
	}
	return nil
}

func ParseKind(s string) (Kind, error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "none", "off":
		return KindNone, nil
	case "http", "http-connect", "connect":
		return KindHTTP, nil
	case "sam", "i2p-sam":
		return KindSAM, nil
	case "https", "https-connect":
		// TLS to the proxy itself. Never aliased to plaintext HTTP.
		return KindHTTPS, nil
	case "socks5", "socks":
		return KindSOCKS5, nil
	default:
		return KindNone, fmt.Errorf("unknown upstream kind %q", s)
	}
}

func Validate(c Config) error {
	if !c.Enabled() {
		if c.Kind == KindNone || c.Host == "" {
			return nil
		}
		if !ValidPort(c.Port) {
			return fmt.Errorf("port must be between %d and %d", MinPort, MaxPort)
		}
		return fmt.Errorf("upstream host is required")
	}
	if err := ValidateAuth(c.Username, c.Password); err != nil {
		return err
	}
	if _, err := ParseKind(string(c.Kind)); err != nil {
		return err
	}
	if !c.Backend.valid() {
		return fmt.Errorf("unknown backend %q", c.Backend)
	}
	if c.Backend == BackendTor && c.Kind != KindSOCKS5 {
		return fmt.Errorf("the Tor backend needs a SOCKS5 endpoint")
	}
	if c.Backend == BackendI2PHTTP && c.Kind != KindHTTP {
		return fmt.Errorf("the I2P HTTP backend needs an HTTP proxy endpoint")
	}
	if (c.Backend == BackendI2PSAM) != (c.Kind == KindSAM) {
		return fmt.Errorf("the SAM endpoint kind and the i2p-sam backend go together")
	}
	if c.Outproxy != "" && c.Backend != BackendI2PSAM && c.Backend != BackendI2PHTTP {
		return fmt.Errorf("an outproxy only applies to I2P backends")
	}
	if c.Backend == BackendI2PSAM && c.Outproxy != "" && ClassifyName(c.Outproxy) != NameI2P {
		return fmt.Errorf("an I2P outproxy must be an .i2p name")
	}
	if c.ServerName != "" && c.Kind != KindHTTPS {
		return fmt.Errorf("a TLS server name only applies to https proxies")
	}
	if len(c.Username) > 255 || len(c.Password) > 255 {
		return fmt.Errorf("username and password must be at most 255 bytes")
	}
	return nil
}

// LoopPrefixes are literal IPs that must stay off the VPN so the
// phone can still reach the proxy on Wi-Fi or cellular. Hostnames
// cannot be excluded until they resolve. Mesh IPs stay inside the VPN.
func LoopPrefixes(host string) []netip.Prefix {
	host = strings.TrimSpace(host)
	if host == "" {
		return nil
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		// hostname — caller may pass resolved IPs separately
		return nil
	}
	if tsaddr.IsTailscaleIP(addr) || addr.IsLoopback() || addr.IsUnspecified() {
		return nil
	}
	bits := 32
	if addr.Is6() {
		bits = 128
	}
	return []netip.Prefix{netip.PrefixFrom(addr, bits)}
}

// ResolveLoopPrefixes looks up host if it is not already an IP.
func ResolveLoopPrefixes(host string) []netip.Prefix {
	if p := LoopPrefixes(host); len(p) > 0 {
		return p
	}
	host = strings.TrimSpace(host)
	if host == "" {
		return nil
	}
	if _, err := netip.ParseAddr(host); err == nil {
		return nil
	}
	ips, err := net.LookupIP(host)
	if err != nil {
		return nil
	}
	var out []netip.Prefix
	seen := map[netip.Addr]bool{}
	for _, ip := range ips {
		addr, ok := netip.AddrFromSlice(ip)
		if !ok {
			continue
		}
		addr = addr.Unmap()
		if seen[addr] || tsaddr.IsTailscaleIP(addr) || addr.IsLoopback() {
			continue
		}
		seen[addr] = true
		bits := 32
		if addr.Is6() {
			bits = 128
		}
		out = append(out, netip.PrefixFrom(addr, bits))
	}
	return out
}

var (
	mu      sync.RWMutex
	current Config
)

func Set(c Config) error {
	if err := Validate(c); err != nil {
		return err
	}
	if !c.Enabled() {
		c = Config{}
		resetDNSState()
		resetIPv6State()
	} else {
		resetDNSState()
		noteIPv6(IPv6Unknown)
	}
	mu.Lock()
	current = c
	mu.Unlock()
	return nil
}

func Get() Config {
	mu.RLock()
	defer mu.RUnlock()
	return current
}

func Clear() {
	mu.Lock()
	current = Config{}
	mu.Unlock()
	resetDNSState()
	resetIPv6State()
}
