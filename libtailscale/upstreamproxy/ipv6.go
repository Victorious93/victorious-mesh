// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
)

const (
	IPv6Off      = "off"
	IPv6Unknown  = "unknown"
	IPv6ViaProxy = "via_proxy"
	IPv6Blocked  = "blocked"
)

var ErrIPv6Blocked = errors.New("IPv6 blocked: upstream does not support it")

var (
	ipv6Mu    sync.Mutex
	ipv6State = IPv6Off
)

func noteIPv6(state string) {
	ipv6Mu.Lock()
	ipv6State = state
	ipv6Mu.Unlock()
}

func resetIPv6State() {
	ipv6Mu.Lock()
	ipv6State = IPv6Off
	ipv6Mu.Unlock()
}

// IPv6State is "off", "unknown", "via_proxy", or "blocked".
func IPv6State() string {
	ipv6Mu.Lock()
	defer ipv6Mu.Unlock()
	if !Get().Enabled() {
		return IPv6Off
	}
	if ipv6State == IPv6Off {
		return IPv6Unknown
	}
	return ipv6State
}

func destIsIPv6(dest string) bool {
	host, _, err := net.SplitHostPort(dest)
	if err != nil {
		host = dest
	}
	ip, err := netip.ParseAddr(host)
	return err == nil && ip.Is6()
}

func destPort(dest string) (uint16, bool) {
	_, port, err := net.SplitHostPort(dest)
	if err != nil {
		return 0, false
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 0 || n > 65535 {
		return 0, false
	}
	return uint16(n), true
}

// ipv6RejectedByProxy is true when the proxy was reached but refused
// an IPv6 destination (so we must block, not retry on the underlay).
func ipv6RejectedByProxy(err error) bool {
	if err == nil {
		return false
	}
	s := strings.ToLower(err.Error())
	switch {
	case strings.HasPrefix(s, "connect ") || strings.Contains(s, "connect ["):
		return true
	case strings.Contains(s, "socks5"):
		return true
	case strings.Contains(s, "atyp"):
		return true
	case strings.Contains(s, "address family"):
		return true
	case strings.Contains(s, "ipv6"):
		return true
	default:
		return false
	}
}
