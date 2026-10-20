// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

/**
 * Pure policy for auto-connect. Off by default: [enabled] must be true
 * before any start is requested. Does not start when the VPN is already
 * running or when Android has not granted VPN permission.
 */
enum class AutoConnectEvent {
  APP_START,
  BOOT,
  TOGGLE_ON,
}

data class AutoConnectDecision(
    val startVpn: Boolean,
    val reason: String,
)

object AutoConnectPlanner {
  fun decide(
      enabled: Boolean,
      ableToStart: Boolean,
      vpnRunning: Boolean,
      event: AutoConnectEvent,
  ): AutoConnectDecision {
    return when (event) {
      AutoConnectEvent.APP_START,
      AutoConnectEvent.BOOT,
      AutoConnectEvent.TOGGLE_ON ->
          when {
            !enabled -> AutoConnectDecision(false, "auto-connect off")
            vpnRunning -> AutoConnectDecision(false, "already running")
            !ableToStart -> AutoConnectDecision(false, "VPN permission not granted")
            else ->
                AutoConnectDecision(
                    true,
                    when (event) {
                      AutoConnectEvent.APP_START -> "start last tunnel on app start"
                      AutoConnectEvent.BOOT -> "start last tunnel on boot"
                      AutoConnectEvent.TOGGLE_ON -> "start last tunnel after enable"
                    },
                )
          }
    }
  }
}
