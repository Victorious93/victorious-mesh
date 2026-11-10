// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"testing"
	"time"
)

func drainPower() {
	for {
		select {
		case <-powerSaverChanged:
		default:
			return
		}
	}
}

func TestPowerSaverIntervals(t *testing.T) {
	defer SetPowerSaver(false)
	SetPowerSaver(false)
	drainPower()
	if LoopWatchInterval() != 3*time.Minute {
		t.Fatalf("normal interval = %v", LoopWatchInterval())
	}
	SetPowerSaver(true)
	if !PowerSaver() || LoopWatchInterval() != 15*time.Minute {
		t.Fatalf("saver interval = %v", LoopWatchInterval())
	}
	select {
	case <-PowerSaverChanged():
	default:
		t.Fatal("no change signal")
	}
	SetPowerSaver(true) // no flip, no signal
	select {
	case <-PowerSaverChanged():
		t.Fatal("unexpected signal without a change")
	default:
	}
}

func TestPowerSaverSignalCoalesces(t *testing.T) {
	defer SetPowerSaver(false)
	SetPowerSaver(false)
	drainPower()
	for i := 0; i < 5; i++ {
		SetPowerSaver(i%2 == 0) // must never block
	}
	n := 0
	for {
		select {
		case <-PowerSaverChanged():
			n++
			continue
		default:
		}
		break
	}
	if n != 1 {
		t.Fatalf("signals = %d, want 1 (coalesced)", n)
	}
}
