// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.killswitch

/**
 * Pure policy for the kill switch. Off by default: [enabled] must be true
 * before anything is armed or held.
 *
 * User-initiated disconnect always tears down. Unexpected drops while armed
 * hold a blackhole VPN and ask the service to reconnect. Revoke cannot hold
 * the VPN slot (another app owns it).
 */
enum class KillSwitchEvent {
  USER_START,
  USER_STOP,
  UNEXPECTED_DROP,
  REVOKE,
  ALWAYS_ON_OR_RESTART,
  TOGGLE_ON,
  TOGGLE_OFF,
}

data class KillSwitchDecision(
    /** true = arm, false = disarm, null = leave [armed] unchanged. */
    val arm: Boolean? = null,
    val holdBlackhole: Boolean = false,
    val restartVpn: Boolean = false,
    val clearBlackhole: Boolean = false,
    val reason: String,
)

object KillSwitchPlanner {
  fun decide(
      enabled: Boolean,
      armed: Boolean,
      vpnRunning: Boolean,
      event: KillSwitchEvent,
  ): KillSwitchDecision {
    return when (event) {
      KillSwitchEvent.USER_START ->
          if (enabled) {
            KillSwitchDecision(arm = true, clearBlackhole = true, reason = "user start; arm")
          } else {
            KillSwitchDecision(clearBlackhole = true, reason = "user start; kill switch off")
          }
      KillSwitchEvent.USER_STOP ->
          KillSwitchDecision(arm = false, reason = "explicit disconnect")
      KillSwitchEvent.UNEXPECTED_DROP ->
          if (enabled && armed) {
            KillSwitchDecision(
                holdBlackhole = true,
                reason = "unexpected drop; hold until reconnect or service restart",
            )
          } else {
            KillSwitchDecision(reason = "drop; kill switch not holding")
          }
      KillSwitchEvent.REVOKE ->
          KillSwitchDecision(
              arm = false,
              reason = "another VPN took the slot; cannot hold",
          )
      KillSwitchEvent.ALWAYS_ON_OR_RESTART ->
          if (enabled) {
            KillSwitchDecision(
                arm = true,
                restartVpn = !vpnRunning,
                reason = "always-on or service restart",
            )
          } else {
            KillSwitchDecision(reason = "always-on; kill switch off")
          }
      KillSwitchEvent.TOGGLE_ON ->
          if (vpnRunning) {
            KillSwitchDecision(arm = true, reason = "arm existing tunnel")
          } else {
            KillSwitchDecision(reason = "on; wait for connect")
          }
      KillSwitchEvent.TOGGLE_OFF ->
          KillSwitchDecision(arm = false, reason = "kill switch off")
    }
  }
}
