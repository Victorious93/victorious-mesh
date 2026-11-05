// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"net/netip"
	"slices"
	"strings"
	"sync"
)

// The proxy host must stay off the VPN (LocalRoutes). When the host is
// a name, its addresses can change after the routes were built; the
// watcher re-resolves and asks for a reconfigure when they differ.

var (
	loopMu      sync.Mutex
	loopApplied []netip.Prefix

	// resolveLoop is swapped in tests.
	resolveLoop = ResolveLoopPrefixes

	proxyDialFailed = make(chan struct{}, 1)
)

// NoteLoopPrefixes records the proxy exclusions that were applied.
func NoteLoopPrefixes(p []netip.Prefix) {
	loopMu.Lock()
	loopApplied = sortedPrefixes(p)
	loopMu.Unlock()
}

// HostIsName is true when host is a hostname (not an IP literal).
func HostIsName(host string) bool {
	host = strings.TrimSpace(host)
	if host == "" {
		return false
	}
	_, err := netip.ParseAddr(host)
	return err != nil
}

// LoopPrefixesStale re-resolves host and reports whether the result
// differs from what was applied. A failed lookup is not stale (keep
// the old exclusion rather than dropping it).
func LoopPrefixesStale(host string) bool {
	if !HostIsName(host) {
		return false
	}
	fresh := sortedPrefixes(resolveLoop(host))
	if len(fresh) == 0 {
		return false
	}
	loopMu.Lock()
	defer loopMu.Unlock()
	return !slices.Equal(fresh, loopApplied)
}

// ProxyDialFailures fires (coalesced) when reaching the proxy fails.
func ProxyDialFailures() <-chan struct{} { return proxyDialFailed }

func noteProxyDialFailed() {
	select {
	case proxyDialFailed <- struct{}{}:
	default:
	}
}

func sortedPrefixes(p []netip.Prefix) []netip.Prefix {
	out := slices.Clone(p)
	slices.SortFunc(out, func(a, b netip.Prefix) int {
		if c := a.Addr().Compare(b.Addr()); c != 0 {
			return c
		}
		return a.Bits() - b.Bits()
	})
	return slices.Compact(out)
}
