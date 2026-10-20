package com.tailscale.ipn.killswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KillSwitchPlannerTest {
  @Test
  fun defaultsDoNothingOnDrop() {
    val d =
        KillSwitchPlanner.decide(
            enabled = false,
            armed = false,
            vpnRunning = false,
            event = KillSwitchEvent.UNEXPECTED_DROP,
        )
    assertFalse(d.holdBlackhole)
    assertFalse(d.restartVpn)
    assertNull(d.arm)
  }

  @Test
  fun userStartArmsOnlyWhenEnabled() {
    val off =
        KillSwitchPlanner.decide(false, false, false, KillSwitchEvent.USER_START)
    assertNull(off.arm)
    assertTrue(off.clearBlackhole)

    val on = KillSwitchPlanner.decide(true, false, false, KillSwitchEvent.USER_START)
    assertEquals(true, on.arm)
    assertTrue(on.clearBlackhole)
  }

  @Test
  fun userStopAlwaysDisarms() {
    val d = KillSwitchPlanner.decide(true, true, true, KillSwitchEvent.USER_STOP)
    assertEquals(false, d.arm)
    assertFalse(d.holdBlackhole)
    assertFalse(d.restartVpn)
  }

  @Test
  fun unexpectedDropHoldsWhenArmed() {
    val d = KillSwitchPlanner.decide(true, true, false, KillSwitchEvent.UNEXPECTED_DROP)
    assertTrue(d.holdBlackhole)
    assertFalse(d.restartVpn)
  }

  @Test
  fun unexpectedDropIgnoredWhenEnabledButNotArmed() {
    val d = KillSwitchPlanner.decide(true, false, false, KillSwitchEvent.UNEXPECTED_DROP)
    assertFalse(d.holdBlackhole)
    assertFalse(d.restartVpn)
  }

  @Test
  fun revokeGivesUp() {
    val d = KillSwitchPlanner.decide(true, true, true, KillSwitchEvent.REVOKE)
    assertEquals(false, d.arm)
    assertFalse(d.holdBlackhole)
    assertFalse(d.restartVpn)
    assertTrue(d.reason.contains("cannot hold"))
  }

  @Test
  fun alwaysOnArmsWhenEnabled() {
    val running = KillSwitchPlanner.decide(true, false, true, KillSwitchEvent.ALWAYS_ON_OR_RESTART)
    assertEquals(true, running.arm)
    assertFalse(running.restartVpn)

    val down = KillSwitchPlanner.decide(true, false, false, KillSwitchEvent.ALWAYS_ON_OR_RESTART)
    assertEquals(true, down.arm)
    assertTrue(down.restartVpn)
  }

  @Test
  fun toggleOnArmsOnlyIfVpnRunning() {
    val idle = KillSwitchPlanner.decide(true, false, false, KillSwitchEvent.TOGGLE_ON)
    assertNull(idle.arm)

    val live = KillSwitchPlanner.decide(true, false, true, KillSwitchEvent.TOGGLE_ON)
    assertEquals(true, live.arm)
  }

  @Test
  fun toggleOffDisarms() {
    val d = KillSwitchPlanner.decide(true, true, true, KillSwitchEvent.TOGGLE_OFF)
    assertEquals(false, d.arm)
    assertFalse(d.holdBlackhole)
  }
}
