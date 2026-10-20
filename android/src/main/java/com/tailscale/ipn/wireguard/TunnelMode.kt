// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.wireguard

/**
 * Android allows a single VpnService per app, and IPNService is its only owner. The two tunnel
 * modes are mutually exclusive: switching always tears the current tunnel down first.
 */
enum class TunnelMode {
  MESH,
  WIREGUARD,
}

data class TunnelState(
    val mode: TunnelMode = TunnelMode.MESH,
    val running: Boolean = false,
    val wireGuardProfileId: String? = null,
)

sealed class TunnelAction {
  object StopMesh : TunnelAction()

  object StopWireGuard : TunnelAction()

  object StartMesh : TunnelAction()

  data class StartWireGuard(val profileId: String) : TunnelAction()
}

/** What the user asked for. A null target means "disconnect everything". */
sealed class TunnelTarget {
  object Mesh : TunnelTarget()

  data class WireGuard(val profileId: String) : TunnelTarget()

  object Off : TunnelTarget()
}

/** Pure, JVM-testable planning of a mode switch. */
object TunnelModeController {
  fun plan(current: TunnelState, target: TunnelTarget): List<TunnelAction> {
    val stopCurrent =
        when {
          !current.running -> emptyList()
          current.mode == TunnelMode.MESH -> listOf(TunnelAction.StopMesh)
          else -> listOf(TunnelAction.StopWireGuard)
        }
    return when (target) {
      TunnelTarget.Off -> stopCurrent
      TunnelTarget.Mesh ->
          if (current.running && current.mode == TunnelMode.MESH) emptyList()
          else stopCurrent + TunnelAction.StartMesh
      is TunnelTarget.WireGuard ->
          if (current.running &&
              current.mode == TunnelMode.WIREGUARD &&
              current.wireGuardProfileId == target.profileId)
              emptyList()
          else stopCurrent + TunnelAction.StartWireGuard(target.profileId)
    }
  }

  /** State after applying a successful plan. */
  fun next(current: TunnelState, target: TunnelTarget): TunnelState =
      when (target) {
        TunnelTarget.Off -> current.copy(running = false)
        TunnelTarget.Mesh -> TunnelState(TunnelMode.MESH, true, null)
        is TunnelTarget.WireGuard -> TunnelState(TunnelMode.WIREGUARD, true, target.profileId)
      }
}
