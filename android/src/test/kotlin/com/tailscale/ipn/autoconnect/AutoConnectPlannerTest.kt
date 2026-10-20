package com.tailscale.ipn.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPlannerTest {
  @Test
  fun offByDefaultNeverStarts() {
    for (event in AutoConnectEvent.values()) {
      val d = AutoConnectPlanner.decide(false, true, false, event)
      assertFalse(event.name, d.startVpn)
      assertEquals("auto-connect off", d.reason)
    }
  }

  @Test
  fun startsWhenEnabledAbleAndIdle() {
    for (event in AutoConnectEvent.values()) {
      val d = AutoConnectPlanner.decide(true, true, false, event)
      assertTrue(event.name, d.startVpn)
    }
  }

  @Test
  fun skipsWhenAlreadyRunning() {
    val d = AutoConnectPlanner.decide(true, true, true, AutoConnectEvent.APP_START)
    assertFalse(d.startVpn)
    assertEquals("already running", d.reason)
  }

  @Test
  fun skipsWithoutVpnPermission() {
    val d = AutoConnectPlanner.decide(true, false, false, AutoConnectEvent.BOOT)
    assertFalse(d.startVpn)
    assertEquals("VPN permission not granted", d.reason)
  }
}
