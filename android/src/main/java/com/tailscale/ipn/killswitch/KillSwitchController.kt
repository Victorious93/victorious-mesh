// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.killswitch

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.tailscale.ipn.StartVPNWorker
import com.tailscale.ipn.util.TSLog

/** Applies [KillSwitchPlanner] to the persisted store. Off by default. */
object KillSwitchController {
  private const val TAG = "KillSwitch"
  private const val RESTART_COOLDOWN_MS = 3_000L

  @Volatile private var lastRestartElapsedMs: Long = 0L

  fun store(): KillSwitchStore = KillSwitchStore.fromApp()

  @Synchronized
  fun apply(event: KillSwitchEvent, vpnRunning: Boolean): KillSwitchDecision {
    val s = store()
    val decision = KillSwitchPlanner.decide(s.enabled(), s.armed(), vpnRunning, event)
    decision.arm?.let { s.setArmed(it) }
    TSLog.d(TAG, "${event.name}: ${decision.reason}")
    return decision
  }

  fun shouldRestartNow(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastRestartElapsedMs < RESTART_COOLDOWN_MS) return false
    lastRestartElapsedMs = now
    return true
  }

  /**
   * Restart through WorkManager (expedited) instead of startForegroundService
   * from a dying service, which Android 12+ may refuse from the background.
   */
  fun scheduleRestart(ctx: Context) {
    val req =
        OneTimeWorkRequest.Builder(StartVPNWorker::class.java)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    WorkManager.getInstance(ctx).enqueueUniqueWork("killswitch-restart", ExistingWorkPolicy.KEEP, req)
  }
}
