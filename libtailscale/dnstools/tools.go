// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package dnstools

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

// Compare runs one lookup against each profile concurrently (at most
// 4 at a time) with the cache bypassed, so the numbers are real network
// round trips, and reports how the answers differ.
type Comparison struct {
	Results []*Result `json:"results"`
	// Agree is true when every successful profile returned the same
	// answer data (order and TTL ignored).
	Agree   bool   `json:"agree"`
	Fastest string `json:"fastest,omitempty"`
	Summary string `json:"summary"`
}

func (r *Resolver) Compare(ctx context.Context, profiles []Profile, name string, qt QType, opt Options) Comparison {
	opt.NoCache = true
	out := make([]*Result, len(profiles))
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for i, p := range profiles {
		wg.Add(1)
		go func(i int, p Profile) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			out[i] = r.Query(ctx, p, name, qt, opt)
		}(i, p)
	}
	wg.Wait()
	c := Comparison{Results: out, Agree: true}
	var ref string
	var fastest *Result
	okCount := 0
	for _, res := range out {
		if !res.OK() {
			continue
		}
		okCount++
		sig := res.RCode + "|" + answerSig(res.Answer)
		if ref == "" {
			ref = sig
		} else if sig != ref {
			c.Agree = false
		}
		if fastest == nil || res.Millis < fastest.Millis {
			fastest = res
		}
	}
	if fastest != nil {
		c.Fastest = fastest.Profile
	}
	switch {
	case okCount == 0:
		c.Agree = false
		c.Summary = "No resolver gave a usable answer."
	case okCount < len(out):
		c.Summary = fmt.Sprintf("%d of %d resolvers answered; %d failed.", okCount, len(out), len(out)-okCount)
	}
	if okCount > 1 {
		if c.Agree {
			c.Summary += " All answering resolvers agree."
		} else {
			c.Summary += " Resolvers disagree (this can be normal for CDNs and split DNS)."
		}
	}
	c.Summary = strings.TrimSpace(c.Summary)
	return c
}

func answerSig(rrs []RR) string {
	s := make([]string, 0, len(rrs))
	for _, r := range rrs {
		s = append(s, r.Type+" "+r.Data)
	}
	sort.Strings(s)
	return strings.Join(s, ";")
}

// ---------------------------------------------------------------- history

// History is an optional, bounded record of lookups. Disabled (and
// empty) by default; turning it off clears it.
type History struct {
	mu      sync.Mutex
	enabled bool
	max     int
	items   []*Result
}

const defaultHistoryMax = 100

func NewHistory() *History { return &History{max: defaultHistoryMax} }

func (h *History) SetEnabled(on bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.enabled = on
	if !on {
		h.items = nil
	}
}

func (h *History) Enabled() bool { h.mu.Lock(); defer h.mu.Unlock(); return h.enabled }

// Add records r only while enabled.
func (h *History) Add(r *Result) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.enabled || r == nil {
		return
	}
	h.items = append(h.items, r)
	if len(h.items) > h.max {
		h.items = h.items[len(h.items)-h.max:]
	}
}

func (h *History) Clear() { h.mu.Lock(); h.items = nil; h.mu.Unlock() }

// Items returns newest first.
func (h *History) Items() []*Result {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]*Result, len(h.items))
	for i, r := range h.items {
		out[len(h.items)-1-i] = r
	}
	return out
}

// ----------------------------------------------------------------- export

// JSON is the machine-readable export.
func (r *Result) JSON() string {
	b, _ := json.MarshalIndent(r, "", "  ")
	return string(b)
}

// Text is a dig-like human rendering, suitable for copying.
func (r *Result) Text() string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "; %s %s via %s (%s over %s) %s\n", r.Name, r.Type, r.Profile, r.Server, r.Transport, r.Route)
	if r.Error != "" {
		fmt.Fprintf(&sb, ";; ERROR: %s\n", r.Error)
		fmt.Fprintf(&sb, ";; Query time: %.1f ms\n", r.Millis)
		return sb.String()
	}
	fl := []string{}
	for _, f := range []struct {
		on bool
		n  string
	}{{r.Flags.AA, "aa"}, {r.Flags.TC, "tc"}, {r.Flags.RD, "rd"}, {r.Flags.RA, "ra"}, {r.Flags.AD, "ad"}, {r.Flags.CD, "cd"}} {
		if f.on {
			fl = append(fl, f.n)
		}
	}
	fmt.Fprintf(&sb, ";; status: %s, flags: %s\n", r.RCode, strings.Join(fl, " "))
	sect := func(title string, rrs []RR) {
		if len(rrs) == 0 {
			return
		}
		fmt.Fprintf(&sb, "\n;; %s SECTION:\n", title)
		for _, rr := range rrs {
			fmt.Fprintf(&sb, "%s\t%d\t%s\t%s\t%s\n", rr.Name, rr.TTL, rr.Class, rr.Type, rr.Data)
		}
	}
	sect("ANSWER", r.Answer)
	sect("AUTHORITY", r.Authority)
	sect("ADDITIONAL", r.Additional)
	fmt.Fprintf(&sb, "\n;; Query time: %.1f ms\n;; Cache: %s", r.Millis, r.Cache)
	if r.CacheTTL > 0 {
		fmt.Fprintf(&sb, " (%ds left)", r.CacheTTL)
	}
	if r.RetriedTCP {
		sb.WriteString("\n;; Truncated over UDP; retried over TCP")
	}
	if r.EDE >= 0 {
		fmt.Fprintf(&sb, "\n;; Extended DNS error: %d", r.EDE)
	}
	fmt.Fprintf(&sb, "\n;; DNSSEC: %s\n", r.DNSSEC.Summary)
	return sb.String()
}

// ------------------------------------------------------------ latency check

// Latency runs n uncached lookups of name/type and reports min/median/max.
type LatencyReport struct {
	Profile string  `json:"profile"`
	Samples int     `json:"samples"`
	OK      int     `json:"ok"`
	MinMs   float64 `json:"minMs"`
	MedMs   float64 `json:"medianMs"`
	MaxMs   float64 `json:"maxMs"`
	Error   string  `json:"error,omitempty"`
}

func (r *Resolver) Latency(ctx context.Context, p Profile, name string, qt QType, n int) LatencyReport {
	if n < 1 {
		n = 1
	}
	if n > 10 {
		n = 10
	}
	rep := LatencyReport{Profile: p.Name, Samples: n}
	var ms []float64
	for i := 0; i < n; i++ {
		if ctx.Err() != nil {
			break
		}
		res := r.Query(ctx, p, name, qt, Options{NoCache: true})
		if res.OK() {
			ms = append(ms, res.Millis)
		} else {
			rep.Error = res.Error
		}
		time.Sleep(10 * time.Millisecond)
	}
	rep.OK = len(ms)
	if len(ms) > 0 {
		sort.Float64s(ms)
		rep.MinMs, rep.MedMs, rep.MaxMs = ms[0], ms[len(ms)/2], ms[len(ms)-1]
		if rep.OK == n {
			rep.Error = ""
		}
	}
	return rep
}
