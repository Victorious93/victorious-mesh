// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.killswitch

import android.os.SystemClock
import com.tailscale.ipn.util.TSLog

/** Applies [KillSwitchPlanner] to the persisted store. Off by default. */
object KillSwitchController {
  private const val TAG = "KillSwitch"
  private const val RESTART_COOLDOWN_MS = 3_000L

  @Volatile var userStopping: Boolean = false
    private set

  @Volatile private var lastRestartElapsedMs: Long = 0L

  fun store(): KillSwitchStore = KillSwitchStore.fromApp()

  fun apply(event: KillSwitchEvent, vpnRunning: Boolean): KillSwitchDecision {
    val s = store()
    val decision = KillSwitchPlanner.decide(s.enabled(), s.armed(), vpnRunning, event)
    decision.arm?.let { s.setArmed(it) }
    when (event) {
      KillSwitchEvent.USER_STOP, KillSwitchEvent.REVOKE, KillSwitchEvent.TOGGLE_OFF ->
          userStopping = true
      KillSwitchEvent.USER_START, KillSwitchEvent.ALWAYS_ON_OR_RESTART -> userStopping = false
      KillSwitchEvent.UNEXPECTED_DROP, KillSwitchEvent.TOGGLE_ON -> Unit
    }
    TSLog.d(TAG, "${event.name}: ${decision.reason}")
    return decision
  }

  fun shouldRestartNow(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastRestartElapsedMs < RESTART_COOLDOWN_MS) return false
    lastRestartElapsedMs = now
    return true
  }
}
