// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"container/list"
	"errors"
	"net/netip"
	"sync"
	"time"
)

// VMap is the bounded name↔virtual-address table behind transparent
// Tor/I2P. A TUN flow carries only a destination IP, so when an app
// asks for abc.onion the answer is a synthetic address from
// [VirtualPrefix]; the later flow to that address is mapped back to the
// name and handed to the backend by name. Entries live in a namespace
// ("tor", "i2p", …) so one backend's policy change cannot be satisfied
// by another's mappings, and each namespace can be invalidated alone.
type VMap struct {
	mu    sync.Mutex
	cap   int
	ttl   time.Duration
	now   func() time.Time
	byKey map[vkey]*list.Element
	byIP  map[netip.Addr]*list.Element
	lru   *list.List // front = most recently used
	next  uint32     // rotating allocator, so freed addresses are not reused soon
	size  uint32
}

type vkey struct{ ns, name string }

type ventry struct {
	key     vkey
	ip      netip.Addr
	expires time.Time
}

// ErrVMapFull is returned only when capacity is zero.
var ErrVMapFull = errors.New("virtual address table unavailable")

// NewVMap makes a table holding at most capacity live names, each valid
// for ttl after it was last requested. now may be nil.
func NewVMap(capacity int, ttl time.Duration, now func() time.Time) *VMap {
	if now == nil {
		now = time.Now
	}
	if capacity <= 0 {
		capacity = 1024
	}
	if cmax := rangeSize() - 2; capacity > cmax {
		capacity = cmax
	}
	if ttl <= 0 {
		ttl = 10 * time.Minute
	}
	return &VMap{cap: capacity, ttl: ttl, now: now,
		byKey: map[vkey]*list.Element{}, byIP: map[netip.Addr]*list.Element{}, lru: list.New()}
}

func rangeSize() int { return 1 << (32 - VirtualPrefix.Bits()) }

func (m *VMap) addrAt(i uint32) netip.Addr {
	b := VirtualPrefix.Addr().As4()
	base := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	v := base + 1 + i%uint32(rangeSize()-2) // skip network and broadcast
	return netip.AddrFrom4([4]byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)})
}

// Assign returns the virtual address for (ns, name), creating or
// refreshing it. At capacity the least recently used entry is dropped.
func (m *VMap) Assign(ns, name string) (netip.Addr, error) {
	name = NormalizeName(name)
	k := vkey{ns, name}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.expireLocked()
	if el, ok := m.byKey[k]; ok {
		e := el.Value.(*ventry)
		e.expires = m.now().Add(m.ttl)
		m.lru.MoveToFront(el)
		return e.ip, nil
	}
	if m.cap <= 0 {
		return netip.Addr{}, ErrVMapFull
	}
	for len(m.byKey) >= m.cap {
		m.removeLocked(m.lru.Back())
	}
	var ip netip.Addr
	for tries := 0; tries < rangeSize(); tries++ {
		ip = m.addrAt(m.next)
		m.next++
		if _, used := m.byIP[ip]; !used {
			break
		}
	}
	e := &ventry{key: k, ip: ip, expires: m.now().Add(m.ttl)}
	el := m.lru.PushFront(e)
	m.byKey[k] = el
	m.byIP[ip] = el
	return ip, nil
}

// Lookup maps a virtual address back to its namespace and name. Expired
// or unknown addresses miss; the caller must then refuse the flow rather
// than guess.
func (m *VMap) Lookup(ip netip.Addr) (ns, name string, ok bool) {
	ip = ip.Unmap()
	m.mu.Lock()
	defer m.mu.Unlock()
	el, found := m.byIP[ip]
	if !found {
		return "", "", false
	}
	e := el.Value.(*ventry)
	if !m.now().Before(e.expires) {
		m.removeLocked(el)
		return "", "", false
	}
	return e.key.ns, e.key.name, true
}

// LookupVirtual implements [NameResolver].
func (m *VMap) LookupVirtual(ip netip.Addr) (string, string, bool) {
	ns, name, ok := m.Lookup(ip)
	return name, ns, ok
}

// Invalidate drops every mapping in ns (a stricter policy, a backend
// change, a restart of the backend). It returns how many were dropped.
func (m *VMap) Invalidate(ns string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for el := m.lru.Front(); el != nil; {
		next := el.Next()
		if el.Value.(*ventry).key.ns == ns {
			m.removeLocked(el)
			n++
		}
		el = next
	}
	return n
}

// Reset drops everything.
func (m *VMap) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.byKey = map[vkey]*list.Element{}
	m.byIP = map[netip.Addr]*list.Element{}
	m.lru.Init()
}

// Len is the number of live (unexpired) mappings.
func (m *VMap) Len() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.expireLocked()
	return len(m.byKey)
}

func (m *VMap) expireLocked() {
	now := m.now()
	for el := m.lru.Back(); el != nil; {
		prev := el.Prev()
		if now.Before(el.Value.(*ventry).expires) {
			// LRU order is by last use, not expiry, so a still-valid
			// tail does not prove the rest are valid; scan fully.
			el = prev
			continue
		}
		m.removeLocked(el)
		el = prev
	}
}

func (m *VMap) removeLocked(el *list.Element) {
	if el == nil {
		return
	}
	e := el.Value.(*ventry)
	delete(m.byKey, e.key)
	delete(m.byIP, e.ip)
	m.lru.Remove(el)
}
