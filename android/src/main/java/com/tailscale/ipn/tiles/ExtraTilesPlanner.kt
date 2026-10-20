// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.tiles

/** Pure policy for extra QS tiles and the home-screen widget. Off by default. */
enum class ExtraComponent {
  KILL_SWITCH_TILE,
  PROXY_SHARE_TILE,
  STATUS_TILE,
  HOME_WIDGET,
}

enum class ExtraTileKind {
  KILL_SWITCH,
  PROXY_SHARE,
  STATUS,
}

enum class ExtraClickAction {
  NONE,
  TOGGLE_KILL_SWITCH_ON,
  TOGGLE_KILL_SWITCH_OFF,
  TOGGLE_PROXY_ON,
  TOGGLE_PROXY_OFF,
  OPEN_APP,
  START_VPN,
  STOP_VPN,
}

data class ExtraClickDecision(
    val action: ExtraClickAction,
    val reason: String,
)

object ExtraTilesPlanner {
  fun visibleComponents(enabled: Boolean): Set<ExtraComponent> =
      if (enabled) ExtraComponent.entries.toSet() else emptySet()

  fun statusLine(connected: Boolean, meshIpv4: String?): String =
      when {
        !connected -> "Not connected"
        meshIpv4.isNullOrBlank() -> "Connected"
        else -> "Connected · $meshIpv4"
      }

  fun widgetBody(connected: Boolean, meshIpv4: String?, proxyListening: Boolean): String {
    val status = statusLine(connected, meshIpv4)
    return if (proxyListening) "$status · proxy on" else status
  }

  fun decideTileClick(
      kind: ExtraTileKind,
      extrasEnabled: Boolean,
      killSwitchOn: Boolean,
      proxyOn: Boolean,
      meshReady: Boolean,
  ): ExtraClickDecision {
    if (!extrasEnabled) {
      return ExtraClickDecision(ExtraClickAction.NONE, "extra tiles off")
    }
    return when (kind) {
      ExtraTileKind.KILL_SWITCH ->
          if (killSwitchOn) {
            ExtraClickDecision(ExtraClickAction.TOGGLE_KILL_SWITCH_OFF, "turn kill switch off")
          } else {
            ExtraClickDecision(ExtraClickAction.TOGGLE_KILL_SWITCH_ON, "turn kill switch on")
          }
      ExtraTileKind.PROXY_SHARE ->
          when {
            proxyOn -> ExtraClickDecision(ExtraClickAction.TOGGLE_PROXY_OFF, "stop mesh proxy")
            !meshReady -> ExtraClickDecision(ExtraClickAction.OPEN_APP, "mesh not ready for proxy")
            else -> ExtraClickDecision(ExtraClickAction.TOGGLE_PROXY_ON, "start mesh proxy")
          }
      ExtraTileKind.STATUS -> ExtraClickDecision(ExtraClickAction.OPEN_APP, "status tile opens the app")
    }
  }

  fun decideWidgetClick(
      extrasEnabled: Boolean,
      vpnRunning: Boolean,
      ableToStart: Boolean,
  ): ExtraClickDecision {
    if (!extrasEnabled) {
      return ExtraClickDecision(ExtraClickAction.NONE, "extra tiles off")
    }
    if (!ableToStart) {
      return ExtraClickDecision(ExtraClickAction.OPEN_APP, "VPN permission not granted")
    }
    return if (vpnRunning) {
      ExtraClickDecision(ExtraClickAction.STOP_VPN, "widget stop")
    } else {
      ExtraClickDecision(ExtraClickAction.START_VPN, "widget start")
    }
  }
}
