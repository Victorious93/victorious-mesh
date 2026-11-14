// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"crypto/sha3"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

func makeOnion(seed byte) string {
	pub := make([]byte, 32)
	for i := range pub {
		pub[i] = seed + byte(i)
	}
	h := sha3.New256()
	h.Write([]byte(".onion checksum"))
	h.Write(pub)
	h.Write([]byte{3})
	sum := h.Sum(nil)
	raw := append(append(append([]byte(nil), pub...), sum[:2]...), 3)
	return strings.ToLower(b32.EncodeToString(raw)) + ".onion"
}

func TestOnionV3Validation(t *testing.T) {
	good := makeOnion(7)
	if len(good) != 56+6 {
		t.Fatalf("len %d", len(good))
	}
	for _, n := range []string{good, strings.ToUpper(good), good + ".", "www." + good} {
		if ClassifyName(n) != NameOnion {
			t.Fatalf("%q should be a valid onion", n)
		}
	}
	// A real, publicly listed v3 address (DuckDuckGo) as an external vector.
	if !ValidOnionV3("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion") {
		t.Fatal("known-good v3 address rejected")
	}
	flipped := []byte(good)
	if flipped[3] == 'a' {
		flipped[3] = 'b'
	} else {
		flipped[3] = 'a'
	}
	bad := []string{
		string(flipped),                    // checksum mismatch
		"expyuzz4wqqyqhjn.onion",           // v2 (retired)
		"short.onion",                      // wrong length
		strings.Repeat("a", 56) + ".onion", // right length, bad checksum
		"onion.onion",
		".onion",
	}
	for _, n := range bad {
		if ClassifyName(n) != NameOnionInvalid {
			t.Fatalf("%q must be rejected as invalid onion, got %v", n, ClassifyName(n))
		}
	}
}

func TestClassifyNames(t *testing.T) {
	cases := map[string]NameClass{
		"example.com":       NamePublic,
		"notonion.com":      NamePublic,
		"onion.example.com": NamePublic,
		"forum.i2p":         NameI2P,
		"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p": NameI2P,
		"Printer.LOCAL.": NameLocal,
		"nas.home.arpa":  NameLocal,
		"x.localhost":    NameLocal,
	}
	for n, want := range cases {
		if got := ClassifyName(n); got != want {
			t.Errorf("%q: got %v want %v", n, got, want)
		}
	}
	if !NameI2P.Reserved() || !NameOnion.Reserved() || NamePublic.Reserved() {
		t.Fatal("Reserved()")
	}
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *fakeClock) add(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

func TestVMapAssignLookupExpiry(t *testing.T) {
	clk := &fakeClock{t: time.Unix(1000, 0)}
	m := NewVMap(8, time.Minute, clk.now)
	a, err := m.Assign("tor", makeOnion(1))
	if err != nil || !IsVirtualAddr(a) {
		t.Fatalf("%v %v", a, err)
	}
	if again, _ := m.Assign("tor", strings.ToUpper(makeOnion(1))+"."); again != a {
		t.Fatalf("same name must keep its address: %v vs %v", again, a)
	}
	ns, name, ok := m.Lookup(a)
	if !ok || ns != "tor" || name != makeOnion(1) {
		t.Fatalf("lookup %q %q %v", ns, name, ok)
	}
	// Same name in another namespace is a different mapping (policy separation).
	b, _ := m.Assign("i2p", makeOnion(1))
	if b == a {
		t.Fatal("namespaces share an address")
	}
	clk.add(59 * time.Second)
	if _, _, ok := m.Lookup(a); !ok {
		t.Fatal("expired early")
	}
	clk.add(2 * time.Second)
	if _, _, ok := m.Lookup(a); ok {
		t.Fatal("mapping outlived its TTL")
	}
	if m.Len() != 0 {
		t.Fatalf("len %d", m.Len())
	}
	// Non-virtual or unknown addresses never resolve.
	if _, _, ok := m.Lookup(netip.MustParseAddr("93.184.216.34")); ok {
		t.Fatal("real address resolved")
	}
}

func TestVMapBoundedLRUAndInvalidate(t *testing.T) {
	m := NewVMap(4, time.Hour, nil)
	var ips []netip.Addr
	for i := 0; i < 10; i++ {
		ip, _ := m.Assign("tor", fmt.Sprintf("n%d.i2p", i))
		ips = append(ips, ip)
		if m.Len() > 4 {
			t.Fatalf("exceeded bound: %d", m.Len())
		}
	}
	if _, _, ok := m.Lookup(ips[0]); ok {
		t.Fatal("oldest entry survived eviction")
	}
	if _, _, ok := m.Lookup(ips[9]); !ok {
		t.Fatal("newest entry missing")
	}
	// Addresses are not recycled while the range is far from exhausted.
	seen := map[netip.Addr]bool{}
	for _, ip := range ips {
		if seen[ip] {
			t.Fatal("address reused immediately")
		}
		seen[ip] = true
	}
	m.Assign("i2p", "keep.i2p")
	if n := m.Invalidate("tor"); n != 3 {
		t.Fatalf("invalidated %d", n)
	}
	if _, _, ok := m.Lookup(ips[9]); ok {
		t.Fatal("tor mapping survived invalidation")
	}
	if m.Len() != 1 {
		t.Fatalf("i2p mapping lost: %d", m.Len())
	}
	m.Reset()
	if m.Len() != 0 {
		t.Fatal("reset")
	}
}

func TestVMapConcurrent(t *testing.T) {
	m := NewVMap(64, time.Minute, nil)
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < 500; i++ {
				name := fmt.Sprintf("h%d.i2p", (g*500+i)%100)
				ip, err := m.Assign("i2p", name)
				if err != nil {
					t.Error(err)
					return
				}
				if _, n, ok := m.Lookup(ip); ok && n != name {
					// another goroutine's eviction may reuse nothing so soon
					t.Errorf("address %v maps to %q, want %q", ip, n, name)
					return
				}
				if i%97 == 0 {
					m.Invalidate("i2p")
				}
			}
		}(g)
	}
	wg.Wait()
	if m.Len() > 64 {
		t.Fatal("bound exceeded")
	}
}
