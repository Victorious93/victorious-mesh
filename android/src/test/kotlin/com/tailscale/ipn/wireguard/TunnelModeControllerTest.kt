package com.tailscale.ipn.wireguard

import com.tailscale.ipn.wireguard.TunnelAction.StartMesh
import com.tailscale.ipn.wireguard.TunnelAction.StartWireGuard
import com.tailscale.ipn.wireguard.TunnelAction.StopMesh
import com.tailscale.ipn.wireguard.TunnelAction.StopWireGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelModeControllerTest {
  private val idle = TunnelState()
  private val meshUp = TunnelState(TunnelMode.MESH, true, null)
  private val wgA = TunnelState(TunnelMode.WIREGUARD, true, "a")

  private fun plan(s: TunnelState, t: TunnelTarget) = TunnelModeController.plan(s, t)

  @Test fun idleToWireGuardStartsOnly() = assertEquals(listOf(StartWireGuard("a")), plan(idle, TunnelTarget.WireGuard("a")))

  @Test fun meshToWireGuardTearsDownMeshFirst() =
      assertEquals(listOf(StopMesh, StartWireGuard("a")), plan(meshUp, TunnelTarget.WireGuard("a")))

  @Test fun wireGuardToMeshTearsDownWireGuardFirst() =
      assertEquals(listOf(StopWireGuard, StartMesh), plan(wgA, TunnelTarget.Mesh))

  @Test fun switchingProfilesRestartsTunnel() =
      assertEquals(listOf(StopWireGuard, StartWireGuard("b")), plan(wgA, TunnelTarget.WireGuard("b")))

  @Test fun sameTargetIsNoOp() {
    assertEquals(emptyList<TunnelAction>(), plan(wgA, TunnelTarget.WireGuard("a")))
    assertEquals(emptyList<TunnelAction>(), plan(meshUp, TunnelTarget.Mesh))
  }

  @Test fun offStopsWhateverRuns() {
    assertEquals(listOf(StopWireGuard), plan(wgA, TunnelTarget.Off))
    assertEquals(listOf(StopMesh), plan(meshUp, TunnelTarget.Off))
    assertEquals(emptyList<TunnelAction>(), plan(idle, TunnelTarget.Off))
  }

  @Test fun neverTwoTunnelsAtOnce() {
    for (s in listOf(idle, meshUp, wgA)) for (t in listOf(TunnelTarget.Mesh, TunnelTarget.WireGuard("b"), TunnelTarget.Off)) {
      val p = plan(s, t)
      val firstStart = p.indexOfFirst { it is StartMesh || it is StartWireGuard }
      val lastStop = p.indexOfLast { it == StopMesh || it == StopWireGuard }
      if (firstStart >= 0 && lastStop >= 0) assert(lastStop < firstStart)
      assert(p.count { it is StartMesh || it is StartWireGuard } <= 1)
    }
  }

  @Test fun nextState() {
    assertEquals(TunnelState(TunnelMode.WIREGUARD, true, "b"), TunnelModeController.next(meshUp, TunnelTarget.WireGuard("b")))
    assertEquals(TunnelState(TunnelMode.MESH, true, null), TunnelModeController.next(wgA, TunnelTarget.Mesh))
    assertEquals(wgA.copy(running = false), TunnelModeController.next(wgA, TunnelTarget.Off))
  }
}
