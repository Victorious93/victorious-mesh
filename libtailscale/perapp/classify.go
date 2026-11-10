// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package perapp

import (
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
	"tailscale.com/net/tsaddr"
)

// RouteKind is where one packet or flow goes.
type RouteKind uint8

const (
	// RouteEngine: leave the packet to the mesh engine (mesh peers,
	// subnet routes, and the device exit node if one is set).
	RouteEngine RouteKind = iota
	// RouteDrop: discard (fail-closed).
	RouteDrop
	// RouteForced: userspace stack, device-wide forced proxy.
	RouteForced
	// RouteDirect: userspace stack, protected direct socket.
	RouteDirect
	// RouteProxy: userspace stack, this app's saved proxy.
	RouteProxy
)

func (k RouteKind) String() string {
	switch k {
	case RouteEngine:
		return "engine"
	case RouteDrop:
		return "drop"
	case RouteForced:
		return "forced"
	case RouteDirect:
		return "direct"
	case RouteProxy:
		return "proxy"
	}
	return "unknown"
}

// Route is the decision for one flow.
type Route struct {
	Kind  RouteKind
	UID   int32
	Proxy upstreamproxy.Config // RouteProxy only
}

// UsesStack reports whether the packet must go into the userspace stack.
func (r Route) UsesStack() bool {
	return r.Kind == RouteForced || r.Kind == RouteDirect || r.Kind == RouteProxy
}

// OwnerFunc returns the Android uid that owns a flow, or ok=false if
// the system could not say (Process.INVALID_UID, Android < 10, etc).
type OwnerFunc func(FlowKey) (uid int32, ok bool)

const (
	tcpIdle    = 10 * time.Minute
	udpIdle    = 2 * time.Minute
	maxFlows   = 16384
	unknownUID = -1
)

type flowEntry struct {
	route Route
	last  time.Time
}

// Classifier remembers the decision per flow so the owner lookup (a
// binder call on Android) runs once per new flow, not per packet.
type Classifier struct {
	Owner OwnerFunc
	Now   func() time.Time

	mu         sync.Mutex
	flows      map[FlowKey]*flowEntry
	meshRoutes []netip.Prefix
	gen        uint64 // policy generation the cache was built for
	unknown    atomic.Int64
}

func NewClassifier(owner OwnerFunc) *Classifier {
	return &Classifier{Owner: owner, flows: map[FlowKey]*flowEntry{}}
}

func (c *Classifier) now() time.Time {
	if c.Now != nil {
		return c.Now()
	}
	return time.Now()
}

// SetMeshRoutes records the mesh engine's own routes (mesh ranges and
// subnet routes). Default routes are ignored: those are the internet.
func (c *Classifier) SetMeshRoutes(routes []netip.Prefix) {
	var keep []netip.Prefix
	for _, r := range routes {
		if r.Bits() == 0 {
			continue
		}
		keep = append(keep, r.Masked())
	}
	c.mu.Lock()
	c.meshRoutes = keep
	c.mu.Unlock()
}

// Reset forgets every cached flow (policy changed).
func (c *Classifier) Reset() {
	c.mu.Lock()
	c.flows = map[FlowKey]*flowEntry{}
	c.mu.Unlock()
}

// UnknownOwners counts flows whose app could not be found.
func (c *Classifier) UnknownOwners() int64 { return c.unknown.Load() }

// IsMeshLike reports whether dst stays on the mesh for every app:
// Tailscale addresses and the mesh engine's subnet routes.
func (c *Classifier) IsMeshLike(dst netip.Addr) bool {
	if tsaddr.IsTailscaleIP(dst) {
		return true
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, r := range c.meshRoutes {
		if r.Contains(dst) {
			return true
		}
	}
	return false
}

// DefaultRoute is the device-wide path for an app with no choice.
func DefaultRoute(p Policy, forced bool) Route {
	switch {
	case forced:
		return Route{Kind: RouteForced, UID: unknownUID}
	case p.ExitNodeActive || !p.NeedsCapture():
		return Route{Kind: RouteEngine, UID: unknownUID}
	default:
		// The tunnel took all traffic only for other apps' sake; this
		// app's internet traffic leaves directly, as it would without
		// the VPN capturing it.
		return Route{Kind: RouteDirect, UID: unknownUID}
	}
}

// RouteFor maps an app's rule to a route.
func RouteFor(uid int32, p Policy, forced bool) Route {
	r, ok := p.Rules[uid]
	if !ok || !p.Active() {
		d := DefaultRoute(p, forced)
		d.UID = uid
		return d
	}
	switch r.Action {
	case ActionEngine:
		return Route{Kind: RouteEngine, UID: uid}
	case ActionProxy:
		return Route{Kind: RouteProxy, UID: uid, Proxy: r.Proxy}
	case ActionBlock:
		return Route{Kind: RouteDrop, UID: uid}
	default:
		d := DefaultRoute(p, forced)
		d.UID = uid
		return d
	}
}

// unattributable is the route for packets with no ports (ping, other
// protocols, fragments) to non-mesh addresses.
func unattributable(p Policy, forced bool) Route {
	if p.Strict() {
		return Route{Kind: RouteDrop, UID: unknownUID}
	}
	d := DefaultRoute(p, forced)
	if d.Kind == RouteDirect {
		// The direct relay only carries TCP and UDP.
		return Route{Kind: RouteDrop, UID: unknownUID}
	}
	return d
}

// Classify decides where one outbound packet from the tunnel goes.
// gen is the policy generation; a change drops the flow cache.
func (c *Classifier) Classify(pkt []byte, p Policy, gen uint64, forced bool) Route {
	dst, ok := DestAddr(pkt)
	if !ok {
		return Route{Kind: RouteDrop, UID: unknownUID}
	}
	if c.IsMeshLike(dst) {
		return Route{Kind: RouteEngine, UID: unknownUID}
	}
	key, ok := ParseFlow(pkt)
	if !ok {
		return unattributable(p, forced)
	}
	return c.Lookup(key, p, gen, forced)
}

// Lookup returns the cached route for a flow, asking Owner once.
func (c *Classifier) Lookup(key FlowKey, p Policy, gen uint64, forced bool) Route {
	now := c.now()
	c.mu.Lock()
	if c.gen != gen {
		c.flows = map[FlowKey]*flowEntry{}
		c.gen = gen
	}
	if e, ok := c.flows[key]; ok && now.Sub(e.last) < idleFor(key.Proto) {
		e.last = now
		r := e.route
		c.mu.Unlock()
		return r
	}
	c.mu.Unlock()

	uid := int32(unknownUID)
	if c.Owner != nil {
		if u, ok := c.Owner(key); ok && u >= 0 {
			uid = u
		}
	}
	var r Route
	if uid == unknownUID {
		c.unknown.Add(1)
		if p.Strict() {
			// Fail closed and do not cache: a retransmit asks again.
			return Route{Kind: RouteDrop, UID: unknownUID}
		}
		r = DefaultRoute(p, forced)
	} else {
		r = RouteFor(uid, p, forced)
	}

	c.mu.Lock()
	if c.gen == gen {
		if len(c.flows) >= maxFlows {
			c.sweepLocked(now)
		}
		c.flows[key] = &flowEntry{route: r, last: now}
	}
	c.mu.Unlock()
	return r
}

func idleFor(proto uint8) time.Duration {
	if proto == ProtoTCP {
		return tcpIdle
	}
	return udpIdle
}

func (c *Classifier) sweepLocked(now time.Time) {
	for k, e := range c.flows {
		if now.Sub(e.last) >= idleFor(k.Proto) {
			delete(c.flows, k)
		}
	}
	if len(c.flows) >= maxFlows {
		// Still full: start over. Flows are looked up again, which only
		// costs one more owner query each.
		c.flows = map[FlowKey]*flowEntry{}
	}
}

// Len is the number of cached flows (tests and status).
func (c *Classifier) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.flows)
}
