// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.launch

/** What a launcher (e.g. VictorLaunch) asked for. */
enum class MeshControlAction {
  CONNECT,
  DISCONNECT,
}

/** What the confirm screen does next. */
enum class MeshControlStep {
  /** Nothing: the user cancelled, or nothing needs doing. */
  NOTHING,
  /** Not signed in yet: open the app instead of connecting. */
  OPEN_APP_TO_SIGN_IN,
  /** Android's VPN permission dialog (VpnService.prepare) first. */
  REQUEST_VPN_PERMISSION,
  START_VPN,
  STOP_VPN,
}

/**
 * Pure rules for launcher shortcuts. Contract (do not change): shortcut ids
 * [SHORTCUT_CONNECT]/[SHORTCUT_DISCONNECT], actions [ACTION_CONNECT]/
 * [ACTION_DISCONNECT], handled by MeshControlActivity. Nothing ever happens
 * without the user confirming in Mesh's own dialog; extras are ignored.
 */
object MeshControlPlanner {
  const val ACTION_CONNECT = "com.victorious.mesh.action.MESH_CONNECT"
  const val ACTION_DISCONNECT = "com.victorious.mesh.action.MESH_DISCONNECT"
  const val SHORTCUT_CONNECT = "mesh_connect"
  const val SHORTCUT_DISCONNECT = "mesh_disconnect"

  /** The confirm button stays disabled this long, so a tap meant for the launcher cannot land on it. */
  const val CONFIRM_DELAY_MS = 600L

  fun parse(action: String?): MeshControlAction? =
      when (action) {
        ACTION_CONNECT -> MeshControlAction.CONNECT
        ACTION_DISCONNECT -> MeshControlAction.DISCONNECT
        else -> null
      }

  fun canConfirm(shownForMs: Long): Boolean = shownForMs >= CONFIRM_DELAY_MS

  /** True when the request matches the current state, so only an "OK" is offered. */
  fun alreadyDone(action: MeshControlAction, connected: Boolean): Boolean =
      (action == MeshControlAction.CONNECT && connected) || (action == MeshControlAction.DISCONNECT && !connected)

  fun afterConfirm(
      action: MeshControlAction,
      confirmed: Boolean,
      connected: Boolean,
      ableToStart: Boolean,
      vpnPrepared: Boolean,
  ): MeshControlStep {
    if (!confirmed || alreadyDone(action, connected)) return MeshControlStep.NOTHING
    return when (action) {
      MeshControlAction.CONNECT ->
          when {
            !ableToStart -> MeshControlStep.OPEN_APP_TO_SIGN_IN
            !vpnPrepared -> MeshControlStep.REQUEST_VPN_PERMISSION
            else -> MeshControlStep.START_VPN
          }
      MeshControlAction.DISCONNECT -> MeshControlStep.STOP_VPN
    }
  }

  /** After Android's VPN permission dialog. */
  fun afterVpnPermission(granted: Boolean): MeshControlStep =
      if (granted) MeshControlStep.START_VPN else MeshControlStep.NOTHING

  /** Show the "this also disarms the kill switch" line on a disconnect. */
  fun warnKillSwitch(action: MeshControlAction, killSwitchEnabled: Boolean, armed: Boolean): Boolean =
      action == MeshControlAction.DISCONNECT && killSwitchEnabled && armed
}
