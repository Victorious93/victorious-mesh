// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

import android.content.Context
import android.os.SystemClock
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.WireGuardManager

/** Applies [AutoConnectPlanner] and starts the last tunnel mode. Off by default. */
object AutoConnectController {
  private const val TAG = "AutoConnect"
  private const val START_COOLDOWN_MS = 3_000L

  @Volatile private var lastStartElapsedMs: Long = 0L

  fun store(): AutoConnectStore = AutoConnectStore.fromApp()

  fun maybeStart(ctx: Context, event: AutoConnectEvent) {
    val vpnRunning =
        runCatching { App.get().getAppScopedViewModel().vpnActive.value }.getOrDefault(false)
    val able = UninitializedApp.get().isAbleToStartVPN()
    val decision = AutoConnectPlanner.decide(store().enabled(), able, vpnRunning, event)
    TSLog.d(TAG, "${event.name}: ${decision.reason}")
    if (!decision.startVpn || !shouldStartNow()) return
    startLastTunnel(ctx)
  }

  fun startLastTunnel(ctx: Context) {
    val wg = WireGuardManager.persistedWireGuardProfile()
    if (wg != null) {
      TSLog.d(TAG, "starting WireGuard profile")
      WireGuardManager.connect(ctx, wg)
    } else {
      TSLog.d(TAG, "starting mesh")
      App.get().startVPN()
    }
  }

  private fun shouldStartNow(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastStartElapsedMs < START_COOLDOWN_MS) return false
    lastStartElapsedMs = now
    return true
  }
}
