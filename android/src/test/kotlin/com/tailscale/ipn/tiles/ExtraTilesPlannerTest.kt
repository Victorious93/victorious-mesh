package com.tailscale.ipn.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraTilesPlannerTest {
  @Test
  fun extrasOffHideEveryComponent() {
    assertTrue(ExtraTilesPlanner.visibleComponents(false).isEmpty())
  }

  @Test
  fun extrasOnExposeTilesAndWidget() {
    val got = ExtraTilesPlanner.visibleComponents(true)
    assertEquals(ExtraComponent.entries.toSet(), got)
    assertTrue(ExtraComponent.HOME_WIDGET in got)
    assertTrue(ExtraComponent.STATUS_TILE in got)
  }

  @Test
  fun statusLineFormatsConnectionAndIp() {
    assertEquals("Not connected", ExtraTilesPlanner.statusLine(false, "100.64.1.2"))
    assertEquals("Connected", ExtraTilesPlanner.statusLine(true, null))
    assertEquals("Connected · 100.64.1.2", ExtraTilesPlanner.statusLine(true, "100.64.1.2"))
  }

  @Test
  fun widgetBodyAddsProxyWhenListening() {
    val body = ExtraTilesPlanner.widgetBody(true, "100.64.1.2", true)
    assertTrue(body.contains("100.64.1.2"))
    assertTrue(body.contains("proxy on"))
  }

  @Test
  fun tileClicksDoNothingWhenExtrasOff() {
    ExtraTileKind.entries.forEach { kind ->
      val d = ExtraTilesPlanner.decideTileClick(kind, false, true, true, true)
      assertEquals(ExtraClickAction.NONE, d.action)
    }
  }

  @Test
  fun killSwitchTileToggles() {
    val on =
        ExtraTilesPlanner.decideTileClick(ExtraTileKind.KILL_SWITCH, true, false, false, true)
    assertEquals(ExtraClickAction.TOGGLE_KILL_SWITCH_ON, on.action)
    val off =
        ExtraTilesPlanner.decideTileClick(ExtraTileKind.KILL_SWITCH, true, true, false, true)
    assertEquals(ExtraClickAction.TOGGLE_KILL_SWITCH_OFF, off.action)
  }

  @Test
  fun proxyTileOpensAppWhenMeshNotReady() {
    val d = ExtraTilesPlanner.decideTileClick(ExtraTileKind.PROXY_SHARE, true, false, false, false)
    assertEquals(ExtraClickAction.OPEN_APP, d.action)
  }

  @Test
  fun statusTileNeverStartsVpn() {
    val d = ExtraTilesPlanner.decideTileClick(ExtraTileKind.STATUS, true, false, false, true)
    assertEquals(ExtraClickAction.OPEN_APP, d.action)
    assertFalse(d.action == ExtraClickAction.START_VPN)
  }

  @Test
  fun widgetClickRespectsPermissionAndState() {
    val off = ExtraTilesPlanner.decideWidgetClick(false, false, true)
    assertEquals(ExtraClickAction.NONE, off.action)
    val needPerm = ExtraTilesPlanner.decideWidgetClick(true, false, false)
    assertEquals(ExtraClickAction.OPEN_APP, needPerm.action)
    val start = ExtraTilesPlanner.decideWidgetClick(true, false, true)
    assertEquals(ExtraClickAction.START_VPN, start.action)
    val stop = ExtraTilesPlanner.decideWidgetClick(true, true, true)
    assertEquals(ExtraClickAction.STOP_VPN, stop.action)
  }
}
