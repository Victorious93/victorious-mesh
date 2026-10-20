package com.tailscale.ipn.leakcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakCheckerPlannerTest {
  private fun finding(report: List<LeakFinding>, id: String) = report.first { it.id == id }

  @Test
  fun vpnDownIsFail() {
    val r =
        LeakCheckerPlanner.report(
            LeakInputs(
                vpnActive = false,
                killSwitchOn = false,
                directPackageCount = 0,
                publicIp = "203.0.113.4",
                publicIpIsMesh = false,
                sightingError = null,
                corpDns = true,
            )
        )
    assertEquals(LeakSeverity.FAIL, finding(r, LeakCheckerPlanner.ID_VPN).severity)
    assertEquals(LeakSeverity.FAIL, finding(r, LeakCheckerPlanner.ID_SIGHTING).severity)
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_KILLSWITCH).severity)
  }

  @Test
  fun connectedCleanPathIsOkWithScopeWarn() {
    val r =
        LeakCheckerPlanner.report(
            LeakInputs(
                vpnActive = true,
                killSwitchOn = true,
                directPackageCount = 0,
                publicIp = "203.0.113.4",
                publicIpIsMesh = false,
                sightingError = null,
                corpDns = true,
            )
        )
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_VPN).severity)
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_KILLSWITCH).severity)
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_DIRECT).severity)
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_SIGHTING).severity)
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_DNS).severity)
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_SCOPE).severity)
    assertTrue(finding(r, LeakCheckerPlanner.ID_SCOPE).detail.contains("WebRTC"))
  }

  @Test
  fun directAppsAndMeshSightingAreWarn() {
    val r =
        LeakCheckerPlanner.report(
            LeakInputs(
                vpnActive = true,
                killSwitchOn = true,
                directPackageCount = 2,
                publicIp = "100.64.1.2",
                publicIpIsMesh = true,
                sightingError = null,
                corpDns = false,
            )
        )
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_DIRECT).severity)
    assertTrue(finding(r, LeakCheckerPlanner.ID_DIRECT).detail.contains("2"))
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_SIGHTING).severity)
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_DNS).severity)
  }

  @Test
  fun fetchErrorIsWarn() {
    val r =
        LeakCheckerPlanner.report(
            LeakInputs(
                vpnActive = true,
                killSwitchOn = false,
                directPackageCount = 0,
                publicIp = null,
                publicIpIsMesh = false,
                sightingError = "SocketTimeoutException",
                corpDns = null,
            )
        )
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_SIGHTING).severity)
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_DNS).severity)
  }
}
