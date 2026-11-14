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
    // A setting is configuration, not a measurement.
    assertEquals(LeakSeverity.INCONCLUSIVE, finding(r, LeakCheckerPlanner.ID_DNS).severity)
    assertEquals(LeakEvidence.CONFIGURATION, finding(r, LeakCheckerPlanner.ID_DNS).evidence)
    assertEquals(LeakEvidence.OBSERVED, finding(r, LeakCheckerPlanner.ID_SIGHTING).evidence)
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
    // An unknown setting is not a measurement: inconclusive, not a warning and never OK.
    assertEquals(LeakSeverity.INCONCLUSIVE, finding(r, LeakCheckerPlanner.ID_DNS).severity)
  }

  @Test
  fun onlyHttpsSightingEndpoints() {
    org.junit.Assert.assertTrue(LeakCheckerPlanner.endpointAllowed("https://ipinfo.io/json"))
    org.junit.Assert.assertFalse(LeakCheckerPlanner.endpointAllowed("http://ipinfo.io/json"))
    org.junit.Assert.assertFalse(LeakCheckerPlanner.endpointAllowed("ipinfo.io/json"))
    org.junit.Assert.assertFalse(LeakCheckerPlanner.endpointAllowed("https://"))
  }

  private fun base() =
      LeakInputs(
          vpnActive = true,
          killSwitchOn = true,
          directPackageCount = 0,
          publicIp = "203.0.113.4",
          publicIpIsMesh = false,
          sightingError = null,
          corpDns = true,
      )

  @Test
  fun configurationAloneIsNeverAPass() {
    val r = LeakCheckerPlanner.report(base())
    val (sev, msg) = LeakCheckerPlanner.overall(r)
    assertEquals(LeakSeverity.INCONCLUSIVE, sev)
    assertTrue(msg.contains("not a clean bill"))
    // A public-IP success must not turn the headline green by itself.
    assertEquals(LeakSeverity.OK, finding(r, LeakCheckerPlanner.ID_SIGHTING).severity)
  }

  @Test
  fun observedDnsTestDecidesTheHeadline() {
    val r = LeakCheckerPlanner.report(base())
    fun with(sev: LeakSeverity) = r + LeakFinding(LeakCheckerPlanner.ID_DNS_OBSERVED, sev, "x", LeakEvidence.VERIFIED)
    assertEquals(LeakSeverity.WARN, LeakCheckerPlanner.overall(with(LeakSeverity.OK)).first) // scope warning remains
    assertEquals(LeakSeverity.FAIL, LeakCheckerPlanner.overall(with(LeakSeverity.FAIL)).first)
    assertEquals(LeakSeverity.INCONCLUSIVE, LeakCheckerPlanner.overall(with(LeakSeverity.INCONCLUSIVE)).first)
  }

  @Test
  fun ignoredPerNetworkDnsAndFailOpenProxyAreVisible() {
    val r = LeakCheckerPlanner.report(base().copy(perNetworkDnsIgnored = true, forcedProxyFailOpen = true, overlayDown = "Tor"))
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_PNDNS).severity)
    assertEquals(LeakSeverity.WARN, finding(r, LeakCheckerPlanner.ID_PROXY_OPEN).severity)
    assertTrue(finding(r, LeakCheckerPlanner.ID_OVERLAY).detail.contains("blocked, not leaked"))
  }
}
