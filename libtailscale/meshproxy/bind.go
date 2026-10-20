// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package meshproxy holds bind-address rules for the on-phone SOCKS5 /
// HTTP CONNECT listeners. JVM-adjacent Go tests; no Android imports.
package meshproxy

import (
	"fmt"
	"net/netip"

	"tailscale.com/net/tsaddr"
)

const (
	MinPort = 1024
	MaxPort = 65535
)

// ValidateBind accepts only a Tailscale/Headscale mesh IP and an
// unprivileged port. 0.0.0.0, ::, loopback, link-local, and any
// non-mesh address are rejected.
func ValidateBind(ip string, port int32) error {
	if err := ValidatePort(port); err != nil {
		return err
	}
	return ValidateIP(ip)
}

// ValidateAuth requires both credentials or neither.
func ValidateAuth(username, password string) error {
	if (username == "") != (password == "") {
		return fmt.Errorf("username and password must both be set or both empty")
	}
	return nil
}

func ValidatePort(port int32) error {
	if port < MinPort || port > MaxPort {
		return fmt.Errorf("port must be between %d and %d", MinPort, MaxPort)
	}
	return nil
}

func ValidateIP(ip string) error {
	addr, err := netip.ParseAddr(ip)
	if err != nil {
		return fmt.Errorf("bind address is not a valid IP")
	}
	if !addr.IsValid() || addr.IsUnspecified() || addr.IsLoopback() || addr.IsMulticast() || addr.IsLinkLocalUnicast() {
		return fmt.Errorf("bind address is not a mesh IP")
	}
	if !tsaddr.IsTailscaleIP(addr) {
		return fmt.Errorf("bind address is not a mesh IP")
	}
	return nil
}

// MatchesSelf reports whether ip is one of the node's own mesh
// addresses (with or without a CIDR suffix).
func MatchesSelf(ip string, selfAddresses []string) bool {
	want, err := netip.ParseAddr(ip)
	if err != nil {
		return false
	}
	for _, raw := range selfAddresses {
		got, err := netip.ParsePrefix(raw)
		if err != nil {
			addr, aerr := netip.ParseAddr(raw)
			if aerr != nil {
				continue
			}
			if addr == want {
				return true
			}
			continue
		}
		if got.Addr() == want {
			return true
		}
	}
	return false
}
