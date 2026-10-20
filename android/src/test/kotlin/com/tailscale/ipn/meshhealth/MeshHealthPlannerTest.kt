package com.tailscale.ipn.meshhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshHealthPlannerTest {
  private fun finding(report: MeshHealthReport, id: String) = report.findings.first { it.id == id }

  private fun healthyPeers() =
      listOf(
          MeshPeerSnapshot(online = true, curAddr = "203.0.113.10:41641", ipv4 = "100.64.1.2"),
          MeshPeerSnapshot(online = true, curAddr = "198.51.100.4:41641", ipv4 = "100.64.1.3"),
          MeshPeerSnapshot(online = false, ipv4 = "100.64.1.4"),
      )

  private fun healthyInput(
      peers: List<MeshPeerSnapshot> = healthyPeers(),
      latencyMs: Double? = 14.2,
      pingTarget: String? = "100.64.1.2",
      latencyError: String? = null,
      warningTitles: List<String> = emptyList(),
      vpnActive: Boolean = true,
      wireGuardActive: Boolean = false,
      backendState: String = MeshHealthPlanner.BACKEND_RUNNING,
      rBytes: Long = 2_097_152,
      wBytes: Long = 512,
      numLive: Int = 2,
      statusError: String? = null,
  ) =
      MeshHealthInputs(
          vpnActive = vpnActive,
          wireGuardActive = wireGuardActive,
          backendState = backendState,
          warningTitles = warningTitles,
          peers = peers,
          rBytes = rBytes,
          wBytes = wBytes,
          numLive = numLive,
          latencyMs = latencyMs,
          latencyError = latencyError,
          pingTarget = pingTarget,
          statusError = statusError,
      )

  @Test
  fun connectedDirectMeshIsOk() {
    val r = MeshHealthPlanner.report(healthyInput())
    assertEquals(MeshHealthGrade.OK, r.grade)
    assertEquals(MeshPathQuality.ALL_DIRECT, r.path)
    assertEquals(3, r.peerCounts.total)
    assertEquals(2, r.peerCounts.online)
    assertEquals(2, r.peerCounts.direct)
    assertEquals(0, r.peerCounts.relay)
    assertEquals(MeshHealthSeverity.OK, finding(r, MeshHealthPlanner.ID_GRADE).severity)
    assertEquals(MeshHealthSeverity.OK, finding(r, MeshHealthPlanner.ID_LATENCY).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_LATENCY).detail.contains("14.2 ms"))
    assertTrue(finding(r, MeshHealthPlanner.ID_TRANSFER).detail.contains("2.0 MB"))
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_SCOPE).severity)
  }

  @Test
  fun vpnDownIsDown() {
    val r = MeshHealthPlanner.report(healthyInput(vpnActive = false, latencyMs = null, pingTarget = null))
    assertEquals(MeshHealthGrade.DOWN, r.grade)
    assertEquals(MeshHealthSeverity.FAIL, finding(r, MeshHealthPlanner.ID_VPN).severity)
  }

  @Test
  fun wireGuardModeIsDown() {
    val r = MeshHealthPlanner.report(healthyInput(wireGuardActive = true, latencyMs = null))
    assertEquals(MeshHealthGrade.DOWN, r.grade)
    assertEquals(MeshHealthSeverity.FAIL, finding(r, MeshHealthPlanner.ID_MODE).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_MODE).detail.contains("WireGuard"))
  }

  @Test
  fun backendNotRunningIsDown() {
    val r = MeshHealthPlanner.report(healthyInput(backendState = "Stopped", latencyMs = null))
    assertEquals(MeshHealthGrade.DOWN, r.grade)
    assertEquals(MeshHealthSeverity.FAIL, finding(r, MeshHealthPlanner.ID_BACKEND).severity)
  }

  @Test
  fun statusErrorIsDown() {
    val r =
        MeshHealthPlanner.report(
            healthyInput(
                peers = emptyList(),
                latencyMs = null,
                pingTarget = null,
                statusError = "Timeout",
                backendState = "",
            )
        )
    assertEquals(MeshHealthGrade.DOWN, r.grade)
    assertTrue(finding(r, MeshHealthPlanner.ID_BACKEND).detail.contains("Timeout"))
  }

  @Test
  fun healthWarningsDegrade() {
    val r = MeshHealthPlanner.report(healthyInput(warningTitles = listOf("DERP is sad")))
    assertEquals(MeshHealthGrade.DEGRADED, r.grade)
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_WARNINGS).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_WARNINGS).detail.contains("DERP is sad"))
  }

  @Test
  fun allRelayIsDegraded() {
    val peers =
        listOf(
            MeshPeerSnapshot(online = true, relay = "sea", ipv4 = "100.64.1.2"),
            MeshPeerSnapshot(online = true, peerRelay = "peer-relay", ipv4 = "100.64.1.3"),
        )
    val r = MeshHealthPlanner.report(healthyInput(peers = peers, pingTarget = "100.64.1.2"))
    assertEquals(MeshHealthGrade.DEGRADED, r.grade)
    assertEquals(MeshPathQuality.ALL_RELAY, r.path)
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_PATH).severity)
  }

  @Test
  fun mixedPathsStayOk() {
    val peers =
        listOf(
            MeshPeerSnapshot(online = true, curAddr = "203.0.113.10:41641", ipv4 = "100.64.1.2"),
            MeshPeerSnapshot(online = true, relay = "sea", ipv4 = "100.64.1.3"),
        )
    val r = MeshHealthPlanner.report(healthyInput(peers = peers))
    assertEquals(MeshHealthGrade.OK, r.grade)
    assertEquals(MeshPathQuality.MIXED, r.path)
    assertEquals(1, r.peerCounts.direct)
    assertEquals(1, r.peerCounts.relay)
  }

  @Test
  fun noOnlinePeersIsDegraded() {
    val peers = listOf(MeshPeerSnapshot(online = false, ipv4 = "100.64.1.2"))
    val r = MeshHealthPlanner.report(healthyInput(peers = peers, latencyMs = null, pingTarget = null))
    assertEquals(MeshHealthGrade.DEGRADED, r.grade)
    assertEquals(MeshPathQuality.NONE, r.path)
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_LATENCY).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_LATENCY).detail.contains("No online IPv4"))
  }

  @Test
  fun highLatencyIsWarn() {
    val r = MeshHealthPlanner.report(healthyInput(latencyMs = 400.0))
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_LATENCY).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_LATENCY).detail.contains("high"))
  }

  @Test
  fun pingErrorIsWarn() {
    val r = MeshHealthPlanner.report(healthyInput(latencyMs = null, latencyError = "timeout"))
    assertEquals(MeshHealthSeverity.WARN, finding(r, MeshHealthPlanner.ID_LATENCY).severity)
    assertTrue(finding(r, MeshHealthPlanner.ID_LATENCY).detail.contains("timeout"))
  }

  @Test
  fun firstOnlineIPv4SkipsOfflineAndIpv6() {
    val peers =
        listOf(
            MeshPeerSnapshot(online = false, ipv4 = "100.64.1.9"),
            MeshPeerSnapshot(online = true, ipv4 = null),
            MeshPeerSnapshot(online = true, ipv4 = "100.64.1.2"),
        )
    assertEquals("100.64.1.2", MeshHealthPlanner.firstOnlineIPv4(peers))
    assertNull(MeshHealthPlanner.firstOnlineIPv4(emptyList()))
  }

  @Test
  fun ipv4FromAddressesPicksFirstV4() {
    assertEquals(
        "100.64.1.2",
        MeshHealthPlanner.ipv4FromAddresses(listOf("fd7a:115c::1", "100.64.1.2/32")),
    )
    assertNull(MeshHealthPlanner.ipv4FromAddresses(listOf("fd7a:115c::1")))
    assertNull(MeshHealthPlanner.ipv4FromAddresses(null))
  }

  @Test
  fun formatBytesUsesBinaryUnits() {
    assertEquals("512 B", MeshHealthPlanner.formatBytes(512))
    assertEquals("1.0 KB", MeshHealthPlanner.formatBytes(1024))
    assertEquals("2.0 MB", MeshHealthPlanner.formatBytes(2_097_152))
  }
}
