// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"net"
	"net/netip"
	"testing"
)

func TestLoopPrefixesStale(t *testing.T) {
	old := resolveLoop
	t.Cleanup(func() { resolveLoop = old; NoteLoopPrefixes(nil) })
	a := netip.MustParsePrefix("203.0.113.1/32")
	b := netip.MustParsePrefix("203.0.113.2/32")
	var answer []netip.Prefix
	resolveLoop = func(string) []netip.Prefix { return answer }

	NoteLoopPrefixes([]netip.Prefix{a})
	answer = []netip.Prefix{a}
	if LoopPrefixesStale("proxy.example") {
		t.Fatal("same address should not be stale")
	}
	answer = []netip.Prefix{b}
	if !LoopPrefixesStale("proxy.example") {
		t.Fatal("changed address should be stale")
	}
	answer = nil
	if LoopPrefixesStale("proxy.example") {
		t.Fatal("failed lookup must keep the old exclusion")
	}
	if LoopPrefixesStale("203.0.113.9") {
		t.Fatal("IP literal is never stale")
	}
}

func TestProxyDialFailureSignals(t *testing.T) {
	for len(proxyDialFailed) > 0 {
		<-proxyDialFailed
	}
	d := Dialer{
		Cfg:  Config{Kind: KindHTTP, Host: "127.0.0.1", Port: 1080, FailClosed: true},
		Dial: func(ctx context.Context, n, a string) (net.Conn, error) { return nil, context.DeadlineExceeded },
	}
	if _, err := d.DialTCP(context.Background(), "198.51.100.1:443"); err == nil {
		t.Fatal("want error")
	}
	select {
	case <-ProxyDialFailures():
	default:
		t.Fatal("dial failure not signalled")
	}
}
