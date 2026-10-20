package com.tailscale.ipn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPlannerTest {
  private val self = listOf("100.64.1.2/32", "fd7a:115c:a1e0::2/128")

  @Test
  fun validPortRejectsPrivilegedAndOutOfRange() {
    assertTrue(ProxyPlanner.validPort(1024))
    assertTrue(ProxyPlanner.validPort(1080))
    assertTrue(ProxyPlanner.validPort(65535))
    assertFalse(ProxyPlanner.validPort(1023))
    assertFalse(ProxyPlanner.validPort(0))
    assertFalse(ProxyPlanner.validPort(65536))
  }

  @Test
  fun authCompleteRequiresBothOrNeither() {
    assertTrue(ProxyPlanner.authComplete("", ""))
    assertTrue(ProxyPlanner.authComplete("user", "pass"))
    assertFalse(ProxyPlanner.authComplete("user", ""))
    assertFalse(ProxyPlanner.authComplete("", "pass"))
  }

  @Test
  fun allowedMeshBindAcceptsOnlyThisNodesMeshIp() {
    assertTrue(ProxyPlanner.allowedMeshBind("100.64.1.2", self))
    assertTrue(ProxyPlanner.allowedMeshBind("fd7a:115c:a1e0::2", self))
    assertFalse(ProxyPlanner.allowedMeshBind("100.64.0.1", self))
    assertFalse(ProxyPlanner.allowedMeshBind("0.0.0.0", self))
    assertFalse(ProxyPlanner.allowedMeshBind("::", self))
    assertFalse(ProxyPlanner.allowedMeshBind("127.0.0.1", self))
    assertFalse(ProxyPlanner.allowedMeshBind("192.168.1.1", listOf("192.168.1.1/24")))
    assertFalse(ProxyPlanner.allowedMeshBind("8.8.8.8", listOf("8.8.8.8")))
    assertFalse(ProxyPlanner.allowedMeshBind("", self))
  }

  @Test
  fun firstMeshIPv4StripsCidr() {
    assertEquals("100.64.1.2", ProxyPlanner.firstMeshIPv4(self))
    assertNull(ProxyPlanner.firstMeshIPv4(listOf("fd7a:115c:a1e0::2/128")))
    assertNull(ProxyPlanner.firstMeshIPv4(emptyList()))
  }

  @Test
  fun planOutboundDisabledAndInvalid() {
    val off = ProxyPlanner.planOutbound(UpstreamKind.NONE, "1.2.3.4", 8080, 33)
    assertFalse(off.applyHttpProxy)
    assertNull(off.excludeHost)

    val blank = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "", 8080, 33)
    assertFalse(blank.applyHttpProxy)

    val badPort = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "1.2.3.4", 80, 33)
    assertFalse(badPort.applyHttpProxy)
  }

  @Test
  fun planOutboundSocks5IsSaveOnly() {
    val plan = ProxyPlanner.planOutbound(UpstreamKind.SOCKS5, "1.2.3.4", 1080, 33)
    assertFalse(plan.applyHttpProxy)
    assertNull(plan.excludeHost)
    assertTrue(plan.reason.contains("SOCKS5"))
  }

  @Test
  fun planOutboundHttpRequiresAndroid10() {
    val old = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "1.2.3.4", 8080, 28)
    assertFalse(old.applyHttpProxy)
    val ok = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "1.2.3.4", 8080, 29)
    assertTrue(ok.applyHttpProxy)
    assertEquals("1.2.3.4", ok.excludeHost)
    assertTrue(ok.reason.contains("advisory"))
  }

  @Test
  fun planOutboundForcedExcludesLiteralAndMarksForced() {
    val socks = ProxyPlanner.planOutbound(UpstreamKind.SOCKS5, "1.2.3.4", 1080, 28, forced = true)
    assertTrue(socks.forced)
    assertEquals("1.2.3.4", socks.excludeHost)
    assertFalse(socks.applyHttpProxy)
    val http = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "1.2.3.4", 8080, 33, forced = true)
    assertTrue(http.forced)
    assertTrue(http.applyHttpProxy)
  }

  @Test
  fun planOutboundDoesNotExcludeHostnamesOrMeshIps() {
    val host = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "proxy.example.com", 8080, 33)
    assertTrue(host.applyHttpProxy)
    assertNull(host.excludeHost)

    val mesh = ProxyPlanner.planOutbound(UpstreamKind.HTTP, "100.64.9.9", 8080, 33)
    assertTrue(mesh.applyHttpProxy)
    assertNull(mesh.excludeHost)
  }
}
