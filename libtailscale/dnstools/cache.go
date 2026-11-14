// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package dnstools

import (
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

type cacheKey struct {
	profile string
	name    string
	qt      QType
	dnssec  bool
}

type cacheEntry struct {
	msg     *Message
	neg     bool
	stored  time.Time
	expires time.Time
	gen     uint64
}

// Cache is a bounded, TTL-aware positive and negative answer cache.
// Entries are scoped to the resolver profile that produced them, so a
// mesh answer is never served for a public profile (or the reverse),
// and to a generation that is bumped whenever policy gets stricter.
type Cache struct {
	mu      sync.Mutex
	max     int
	now     func() time.Time
	gen     uint64
	entries map[cacheKey]*cacheEntry
}

// Cache TTL bounds.
const (
	maxPositiveTTL = 6 * time.Hour
	maxNegativeTTL = 15 * time.Minute
)

func NewCache(max int, now func() time.Time) *Cache {
	if now == nil {
		now = time.Now
	}
	if max <= 0 {
		max = 1024
	}
	return &Cache{max: max, now: now, entries: map[cacheKey]*cacheEntry{}}
}

// Put stores m if it is cacheable: NOERROR with answers (positive, TTL
// = smallest answer TTL), or NXDOMAIN / NOERROR-NODATA with an SOA
// (negative, TTL = min(SOA TTL, SOA MINIMUM), RFC 2308). SERVFAIL,
// REFUSED, truncated and TTL-0 answers are never cached.
func (c *Cache) Put(k cacheKey, m *Message) {
	if m == nil || m.Flags.TC {
		return
	}
	var ttl time.Duration
	neg := false
	switch {
	case m.RCode == "NOERROR" && len(m.Answer) > 0:
		ttl = min(time.Duration(m.MinTTL)*time.Second, maxPositiveTTL)
	case (m.RCode == "NXDOMAIN" || m.RCode == "NOERROR") && m.SOAMin > 0:
		ttl, neg = min(time.Duration(m.SOAMin)*time.Second, maxNegativeTTL), true
	default:
		return
	}
	if ttl <= 0 {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if len(c.entries) >= c.max {
		c.evictLocked(now)
	}
	c.entries[k] = &cacheEntry{msg: m, neg: neg, stored: now, expires: now.Add(ttl), gen: c.gen}
}

func (c *Cache) evictLocked(now time.Time) {
	var oldest cacheKey
	var oldestAt time.Time
	first := true
	for k, e := range c.entries {
		if !now.Before(e.expires) || e.gen != c.gen {
			delete(c.entries, k)
			continue
		}
		if first || e.expires.Before(oldestAt) {
			oldest, oldestAt, first = k, e.expires, false
		}
	}
	if len(c.entries) >= c.max && !first {
		delete(c.entries, oldest)
	}
}

// Get returns a copy of the cached message with TTLs aged to now.
func (c *Cache) Get(k cacheKey) (m *Message, negative bool, ttlLeft uint32, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, found := c.entries[k]
	if !found {
		return nil, false, 0, false
	}
	now := c.now()
	if e.gen != c.gen || !now.Before(e.expires) {
		delete(c.entries, k)
		return nil, false, 0, false
	}
	age := uint32(now.Sub(e.stored) / time.Second)
	cp := *e.msg
	cp.Answer = ageRRs(e.msg.Answer, age)
	cp.Authority = ageRRs(e.msg.Authority, age)
	cp.Additional = ageRRs(e.msg.Additional, age)
	return &cp, e.neg, uint32(e.expires.Sub(now) / time.Second), true
}

func ageRRs(in []RR, age uint32) []RR {
	out := make([]RR, len(in))
	for i, r := range in {
		if r.TTL > age {
			r.TTL -= age
		} else {
			r.TTL = 0
		}
		out[i] = r
	}
	return out
}

// Clear empties the cache.
func (c *Cache) Clear() {
	c.mu.Lock()
	c.entries = map[cacheKey]*cacheEntry{}
	c.mu.Unlock()
}

// Bump invalidates everything without freeing it (cheap, used when a
// stricter policy applies): older-generation entries can never be served.
func (c *Cache) Bump() {
	c.mu.Lock()
	c.gen++
	c.mu.Unlock()
}

// InvalidateProfile drops the entries of one profile.
func (c *Cache) InvalidateProfile(id string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for k := range c.entries {
		if k.profile == id {
			delete(c.entries, k)
			n++
		}
	}
	return n
}

// Len counts live entries.
func (c *Cache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	n := 0
	for _, e := range c.entries {
		if e.gen == c.gen && now.Before(e.expires) {
			n++
		}
	}
	return n
}

// ---------------------------------------------------------- local policy

// LocalPolicy is the user's local host mappings plus optional
// allow/block rules. Patterns: "example.com" (exact) or "*.example.com"
// (subdomains only). Order: Hosts, then Allow (exempts), then Block.
type LocalPolicy struct {
	Hosts map[string][]netip.Addr
	Allow []string
	Block []string
}

func match(pat, name string) bool {
	pat = upstreamproxy.NormalizeName(pat)
	if rest, ok := strings.CutPrefix(pat, "*."); ok {
		return strings.HasSuffix(name, "."+rest)
	}
	return name == pat
}

func anyMatch(pats []string, name string) bool {
	for _, p := range pats {
		if match(p, name) {
			return true
		}
	}
	return false
}

// Apply answers locally if a host mapping or block rule matches. kind
// is "local" or "blocked"; a nil message means no local rule applied.
func (p LocalPolicy) Apply(name string, qt QType) (*Message, string) {
	if addrs, ok := p.Hosts[name]; ok {
		m := &Message{RCode: "NOERROR", EDE: -1}
		for _, a := range addrs {
			a = a.Unmap()
			switch {
			case a.Is4() && qt == TypeA, a.Is6() && qt == TypeAAAA:
				t := string(qt)
				m.Answer = append(m.Answer, RR{Name: name, Type: t, Class: "INET", TTL: 0, Data: a.String()})
			}
		}
		// A name that is mapped but has no record of the asked family is
		// NODATA, not "ask the network": the mapping is authoritative.
		return m, "local"
	}
	if anyMatch(p.Block, name) && !anyMatch(p.Allow, name) {
		return &Message{RCode: "NXDOMAIN", EDE: -1}, "blocked"
	}
	return nil, ""
}
