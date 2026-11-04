package com.tailscale.ipn.fleet

import com.tailscale.ipn.fleet.theme.FleetThemeContract
import com.tailscale.ipn.fleet.theme.FleetThemePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug-keystore certs must never be trusted by a release build. */
class ReleasePinsTest {
  private val dcaDebug =
      "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"
  private val dcaRelease =
      "9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D"

  @Test
  fun releaseAllowlistHasOnlyDcaReleaseCert() {
    val release = CallerAllowlist.entriesFor(debug = false)
    assertEquals(setOf(CallerAllowlist.PKG_DCA), release.keys)
    assertEquals(setOf(CallerAllowlist.normalize(dcaRelease)), release[CallerAllowlist.PKG_DCA])
    assertEquals(
        CallerScope.NONE,
        CallerAllowlist.scopeFor(mapOf(CallerAllowlist.PKG_DCA to listOf(dcaDebug)), release),
    )
    assertEquals(
        CallerScope.NONE,
        CallerAllowlist.scopeFor(mapOf(CallerAllowlist.PKG_DCA_DEBUG to listOf(dcaDebug)), release),
    )
    assertEquals(
        CallerScope.NONE,
        CallerAllowlist.scopeFor(
            mapOf(CallerAllowlist.PKG_BROWSER to listOf(CallerAllowlist.BROWSER_DEBUG_CERT)), release),
    )
    assertEquals(
        CallerScope.FULL,
        CallerAllowlist.scopeFor(mapOf(CallerAllowlist.PKG_DCA to listOf(dcaRelease)), release),
    )
  }

  @Test
  fun debugAllowlistKeepsDebugCerts() {
    val debug = CallerAllowlist.entriesFor(debug = true)
    assertEquals(3, debug.size)
    assertEquals(
        CallerScope.FULL,
        CallerAllowlist.scopeFor(mapOf(CallerAllowlist.PKG_DCA_DEBUG to listOf(dcaDebug)), debug),
    )
  }

  @Test
  fun releaseThemeDoesNotPinDebugLaunchCert() {
    assertTrue(FleetThemePlanner.pinnedLaunchCerts(debug = false).isEmpty())
    assertFalse(
        FleetThemePlanner.providerTrusted(
            FleetThemeContract.PACKAGE_LAUNCH, listOf(FleetThemeContract.LAUNCH_DEBUG_CERT), debug = false))
    assertTrue(
        FleetThemePlanner.providerTrusted(
            FleetThemeContract.PACKAGE_LAUNCH, listOf(FleetThemeContract.LAUNCH_DEBUG_CERT), debug = true))
  }
}
