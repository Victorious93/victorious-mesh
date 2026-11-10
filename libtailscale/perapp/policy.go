// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

// Package perapp picks a path for each app's traffic inside the one
// VPN tunnel. Android gives the VPN one TUN for every app, so the app is
// found per flow with ConnectivityManager.getConnectionOwnerUid (asked
// through Owner) and the flow is sent to the mesh engine, the userspace
// stack (direct relay, forced proxy, or that app's own saved proxy), or
// dropped. Off by default. No Android imports; plain Go tests.
package perapp

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
)

// Action is what the Android side resolved for one app (uid).
type Action string

const (
	// ActionDefault: the device-wide path (forced proxy, exit node, or
	// direct internet), as if the app had no per-app choice.
	ActionDefault Action = "default"
	// ActionEngine: hand the flow to the mesh engine, which carries it
	// to the device exit node. Used when the app picked the exit node
	// the device is using, even if a forced proxy is on.
	ActionEngine Action = "engine"
	// ActionProxy: through this app's saved proxy only.
	ActionProxy Action = "proxy"
	// ActionBlock: drop. Used when the chosen path is not available and
	// fail-closed is on (for example the app's exit node is not the
	// device exit node).
	ActionBlock Action = "block"
)

// Rule is one app's resolved choice.
type Rule struct {
	Action Action
	Proxy  upstreamproxy.Config // only for ActionProxy
}

// Policy is the whole per-app table. Zero value is off.
type Policy struct {
	Enabled bool
	// ExitNodeActive: the device has an exit node, so the mesh engine
	// can carry internet traffic for default-route apps.
	ExitNodeActive bool
	Rules          map[int32]Rule
}

// Active reports whether any per-app choice is in force.
func (p Policy) Active() bool { return p.Enabled && len(p.Rules) > 0 }

// NeedsCapture reports whether the tunnel must take all internet
// traffic (0.0.0.0/0 and ::/0) so the chosen apps cannot slip past it.
func (p Policy) NeedsCapture() bool {
	if !p.Active() {
		return false
	}
	for _, r := range p.Rules {
		if r.Action == ActionProxy || r.Action == ActionBlock {
			return true
		}
	}
	return false
}

// Strict reports whether any rule fails closed. Then traffic that
// cannot be matched to an app by port (ping, other protocols, IP
// fragments) to non-mesh addresses is dropped rather than guessed.
func (p Policy) Strict() bool {
	if !p.Active() {
		return false
	}
	for _, r := range p.Rules {
		if r.Action == ActionBlock || (r.Action == ActionProxy && r.Proxy.FailClosed) {
			return true
		}
	}
	return false
}

type wireProxy struct {
	Kind       string `json:"kind"`
	Host       string `json:"host"`
	Port       int32  `json:"port"`
	Username   string `json:"username,omitempty"`
	Password   string `json:"password,omitempty"`
	FailClosed *bool  `json:"failClosed,omitempty"`
}

type wireRule struct {
	UID    int32      `json:"uid"`
	Action string     `json:"action"`
	Proxy  *wireProxy `json:"proxy,omitempty"`
}

type wirePolicy struct {
	Enabled        bool       `json:"enabled"`
	ExitNodeActive bool       `json:"exitNodeActive"`
	Rules          []wireRule `json:"rules"`
}

// Parse reads the JSON policy from the Android side. A rule that is
// malformed becomes ActionBlock rather than being dropped, so a bad
// proxy entry never turns into a silent bypass.
func Parse(raw string) (Policy, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return Policy{}, nil
	}
	var w wirePolicy
	if err := json.Unmarshal([]byte(raw), &w); err != nil {
		return Policy{}, fmt.Errorf("per-app policy: %w", err)
	}
	p := Policy{Enabled: w.Enabled, ExitNodeActive: w.ExitNodeActive, Rules: map[int32]Rule{}}
	for _, wr := range w.Rules {
		if wr.UID < 0 {
			continue
		}
		r := parseRule(wr)
		if prev, ok := p.Rules[wr.UID]; ok {
			r = stricter(prev, r)
		}
		p.Rules[wr.UID] = r
	}
	return p, nil
}

func parseRule(wr wireRule) Rule {
	switch Action(strings.ToLower(strings.TrimSpace(wr.Action))) {
	case ActionDefault:
		return Rule{Action: ActionDefault}
	case ActionEngine:
		return Rule{Action: ActionEngine}
	case ActionBlock:
		return Rule{Action: ActionBlock}
	case ActionProxy:
		if wr.Proxy == nil {
			return Rule{Action: ActionBlock}
		}
		k, err := upstreamproxy.ParseKind(wr.Proxy.Kind)
		if err != nil || k == upstreamproxy.KindNone {
			return Rule{Action: ActionBlock}
		}
		cfg := upstreamproxy.Config{
			Kind:       k,
			Host:       strings.TrimSpace(wr.Proxy.Host),
			Port:       wr.Proxy.Port,
			Username:   wr.Proxy.Username,
			Password:   wr.Proxy.Password,
			FailClosed: wr.Proxy.FailClosed == nil || *wr.Proxy.FailClosed,
		}
		if !cfg.Enabled() || upstreamproxy.Validate(cfg) != nil {
			return Rule{Action: ActionBlock}
		}
		return Rule{Action: ActionProxy, Proxy: cfg}
	default:
		return Rule{Action: ActionBlock}
	}
}

// stricter resolves two packages that share one uid (Android shared
// user IDs). Same choice wins; otherwise the flow is blocked.
func stricter(a, b Rule) Rule {
	if a == b {
		return a
	}
	return Rule{Action: ActionBlock}
}

var (
	mu      sync.RWMutex
	current Policy
)

// Set replaces the active policy.
func Set(p Policy) {
	mu.Lock()
	current = p
	mu.Unlock()
}

// Get returns the active policy.
func Get() Policy {
	mu.RLock()
	defer mu.RUnlock()
	return current
}

// Clear turns per-app routing off.
func Clear() { Set(Policy{}) }
