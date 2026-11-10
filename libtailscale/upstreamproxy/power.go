// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"sync/atomic"
	"time"
)

// Battery saver (off by default) only stretches the periodic proxy-address
// re-check. A failed proxy dial still triggers an immediate re-check, and
// nothing here touches fail-closed, the kill switch, or per-app rules.
const (
	LoopWatchNormal = 3 * time.Minute
	LoopWatchSaver  = 15 * time.Minute
)

var (
	powerSaver        atomic.Bool
	powerSaverChanged = make(chan struct{}, 1)
)

// SetPowerSaver records the battery-saver state and wakes the watcher when
// it changes.
func SetPowerSaver(on bool) {
	if powerSaver.Swap(on) != on {
		select {
		case powerSaverChanged <- struct{}{}:
		default:
		}
	}
}

// PowerSaver reports whether battery saver is active.
func PowerSaver() bool { return powerSaver.Load() }

// PowerSaverChanged fires (coalesced) when the state flips.
func PowerSaverChanged() <-chan struct{} { return powerSaverChanged }

// LoopWatchInterval is how long the watcher waits between periodic checks.
func LoopWatchInterval() time.Duration {
	if PowerSaver() {
		return LoopWatchSaver
	}
	return LoopWatchNormal
}
