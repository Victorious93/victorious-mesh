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
	MinPort = 1024
	MaxPort = 65535
)

type Kind string

const (
	KindNone   Kind = ""
	KindHTTP   Kind = "http"
	KindSOCKS5 Kind = "socks5"
)

// Config is the user-chosen upstream. Zero value is off.
type Config struct {
	Kind       Kind
	Host       string
	Port       int32
	Username   string
	Password   string
	FailClosed bool
}

func (c Config) Enabled() bool {
	return (c.Kind == KindHTTP || c.Kind == KindSOCKS5) && c.Host != "" && ValidPort(c.Port)
}

func (c Config) ProxyAddr() string {
	return net.JoinHostPort(strings.TrimSpace(c.Host), fmt.Sprintf("%d", c.Port))
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
	case "http", "https", "http-connect", "connect":
		return KindHTTP, nil
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
