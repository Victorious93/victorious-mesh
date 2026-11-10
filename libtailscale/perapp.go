// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"
	"net/netip"
	"sync/atomic"

	"github.com/tailscale/tailscale-android/libtailscale/perapp"
	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

// perAppGen bumps on every policy change so cached flow decisions
// from an older policy are thrown away.
var perAppGen atomic.Uint64

var perAppClassifier = perapp.NewClassifier(ownerOfFlow)

// SetPerAppPolicy installs the per-app exit/proxy table (JSON, built by
// the Android side). Off by default; an empty or disabled policy keeps
// the old device-wide behavior.
func SetPerAppPolicy(policyJSON string) error {
	p, err := perapp.Parse(policyJSON)
	if err != nil {
		return err
	}
	prev := perapp.Get()
	perapp.Set(p)
	perAppGen.Add(1)
	perAppClassifier.Reset()
	log.Printf("per-app routing: active=%v rules=%d capture=%v strict=%v", p.Active(), len(p.Rules), p.NeedsCapture(), p.Strict())
	if prev.Active() != p.Active() || prev.NeedsCapture() != p.NeedsCapture() || prev.ExitNodeActive != p.ExitNodeActive {
		reconfigureForcedVPN()
	}
	return nil
}

// ClearPerAppPolicy turns per-app exit/proxy routing off.
func ClearPerAppPolicy() {
	was := perapp.Get().Active()
	perapp.Clear()
	perAppGen.Add(1)
	perAppClassifier.Reset()
	if was {
		reconfigureForcedVPN()
	}
}

// PerAppActive reports whether a per-app policy is in force.
func PerAppActive() bool { return perapp.Get().Active() }

// PerAppUnknownOwners counts flows the phone could not match to an app.
func PerAppUnknownOwners() int64 { return perAppClassifier.UnknownOwners() }

// ownerOfFlow asks Android (ConnectivityManager.getConnectionOwnerUid)
// which app owns a flow.
func ownerOfFlow(k perapp.FlowKey) (int32, bool) {
	svc := currentVPNService()
	if svc == nil {
		return -1, false
	}
	uid := svc.ConnectionOwnerUid(int32(k.Proto), k.Src.Addr().String(), int32(k.Src.Port()), k.Dst.Addr().String(), int32(k.Dst.Port()))
	return uid, uid >= 0
}

// perAppNeedsStack reports whether the userspace stack is needed for
// per-app routing (some flows go direct or through an app's proxy).
func perAppNeedsStack() bool {
	p := perapp.Get()
	return p.Active() && (p.NeedsCapture() || upstreamproxy.Get().Enabled())
}

// dialerForRoute turns a per-app route into the outside leg. nil
// refuses the flow.
func dialerForRoute(r perapp.Route) upstreamproxy.FlowDialer {
	switch r.Kind {
	case perapp.RouteForced:
		return forcedDialer()
	case perapp.RouteDirect:
		return upstreamproxy.DirectDialer{Dial: protectedDial}
	case perapp.RouteProxy:
		d := upstreamproxy.Dialer{Cfg: r.Proxy, Dial: protectedDial, Quiet: true}
		if !r.Proxy.FailClosed {
			d.Direct = protectedDial
		}
		return d
	}
	return nil
}

func forcedDialer() upstreamproxy.FlowDialer {
	cfg := upstreamproxy.Get()
	if !cfg.Enabled() {
		return nil
	}
	return upstreamproxy.Dialer{Cfg: cfg, Dial: protectedDial, Direct: protectedDial}
}

// resolveFlow is the userspace stack's per-flow dialer choice.
func resolveFlow(proto uint8, src, dst netip.AddrPort) upstreamproxy.FlowDialer {
	p := perapp.Get()
	forced := upstreamproxy.Get().Enabled()
	if !p.Active() {
		if forced {
			return forcedDialer()
		}
		return nil
	}
	key := perapp.FlowKey{Proto: proto, Src: src, Dst: dst}
	return dialerForRoute(perAppClassifier.Lookup(key, p, perAppGen.Load(), forced))
}
