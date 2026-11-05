package com.tailscale.ipn.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetCallerScopeTest {
  private val node = "mesh-0123456789abcdef0123456789abcdef"
  private val browserCert = CallerAllowlist.BROWSER_DEBUG_CERT
  private val dcaCert =
      "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"

  private class FakeBackend : MeshBackend {
    var ups = 0
    var downs = 0

    override fun wireGuardModeActive() = false

    override fun status() =
        MeshStatus("Running", true, "https://hs.example", "100.64.0.1", "fd7a:115c:a1e0::1")

    override fun vpnConsentGranted() = true

    override fun up() {
      ups++
    }

    override fun down() {
      downs++
    }
  }

  private fun req(op: String) =
      """{"protocol_major":1,"request_id":"br-1","target_node_id":"$node","operation":"$op","timeout_ms":10000,"parameters":{}}"""

  private fun env(op: String, action: String = "mesh.status"): String {
    val nonce = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    return """{"protocol_major":1,"protocol_minor":1,"op":"$op","requestId":"br-e","targetNodeRef":"$node","actionRef":"$action","params":{},"nonce":"$nonce","timestampMs":1,"ttlMs":120000}"""
  }

  private fun call(
      raw: String,
      scope: CallerScope,
      allowed: Boolean = true,
      enabled: Boolean = true,
      backend: FakeBackend = FakeBackend(),
  ) =
      FleetDispatcher(node, backend, clock = { 1_000L })
          .handle(raw, allowed, enabled, sourceNodeRef = "com.victorious.browser", callerScope = scope)

  private fun assertCat(cat: String, r: BridgeResponse) {
    assertFalse(r.ok)
    assertNull(r.result)
    assertEquals(cat, r.error?.category)
  }

  @Test
  fun browserCertAndPackageAreAllowlistedReadOnly() {
    val browser = mapOf(CallerAllowlist.PKG_BROWSER to listOf(browserCert))
    assertTrue(CallerAllowlist.isAllowed(browser))
    assertEquals(CallerScope.READ_STATUS_IP, CallerAllowlist.scopeFor(browser))
    assertEquals(CallerScope.READ_STATUS_IP, CallerAllowlist.scopeForPackage(CallerAllowlist.PKG_BROWSER))
    assertFalse(
        CallerAllowlist.isAllowed(mapOf(CallerAllowlist.PKG_BROWSER to listOf("00".repeat(32))))
    )
  }

  @Test
  fun droidcommandKeepsFullScope() {
    val dca = mapOf(CallerAllowlist.PKG_DCA to listOf(dcaCert))
    assertEquals(CallerScope.FULL, CallerAllowlist.scopeFor(dca))
    assertTrue(CallerAllowlist.isAllowed(dca))
  }

  @Test
  fun dcaWinsOverBrowserOnSameUid() {
    val both =
        mapOf(
            CallerAllowlist.PKG_BROWSER to listOf(browserCert),
            CallerAllowlist.PKG_DCA to listOf(dcaCert),
        )
    assertEquals(CallerScope.FULL, CallerAllowlist.scopeFor(both))
  }

  @Test
  fun browserMayCallStatusAndIp() {
    for (op in listOf("mesh.status", "mesh.ip.get", "mesh.ip")) {
      val r = call(req(op), CallerScope.READ_STATUS_IP)
      assertTrue("$op should succeed: ${r.error}", r.ok)
    }
  }

  @Test
  fun browserIsForbiddenOnEveryOtherLegacyOp() {
    val forbidden =
        listOf(
            "mesh.up",
            "mesh.down",
            "mesh.capabilities.get",
            "node.status.get",
            "config.export",
            "config.import",
        )
    for (op in forbidden) {
      val r = call(req(op), CallerScope.READ_STATUS_IP)
      assertCat("unauthorized_caller", r)
      assertEquals("caller is not authorized for this operation", r.error?.message)
    }
  }

  @Test
  fun browserCannotUseRemoteEnvelope() {
    for (op in listOf("capabilities.get", "remote.command", "macro.step", "macro.run")) {
      val r = call(env(op, action = "mesh.status"), CallerScope.READ_STATUS_IP)
      assertCat("unauthorized", r)
    }
    val nodeStatus = call(env("remote.command", action = "node.status.get"), CallerScope.READ_STATUS_IP)
    assertCat("unauthorized", nodeStatus)
  }

  @Test
  fun fullScopeStillRunsUpDownAndEnvelope() {
    val be = FakeBackend()
    assertTrue(call(req("mesh.up"), CallerScope.FULL, backend = be).ok)
    assertEquals(1, be.ups)
    assertTrue(call(env("remote.command", action = "mesh.status"), CallerScope.FULL).ok)
  }

  @Test
  fun meshIpIsNeverLoggedOrBackedUp() {
    val r = call(req("mesh.ip.get"), CallerScope.READ_STATUS_IP)
    assertTrue(r.ok)
    val body = FleetProtocol.json.encodeToString(BridgeResponse.serializer(), r)
    assertTrue(body.contains("100.64.0.1"))
    assertFalse(FleetRedaction.redact(body).contains("100.64.0.1"))
    assertFalse(FleetRedaction.redact(body).contains("fd7a:115c:a1e0::1"))
    assertTrue(FleetRedaction.containsMeshAddress(body))
    assertFalse(FleetRedaction.containsMeshAddress(FleetRedaction.redact(body)))

    val backupWithIp = """{"kind":"UNIFIED_BACKUP","ipv4":"100.64.0.1","ips":["100.64.0.1"]}"""
    assertTrue(FleetRedaction.jsonContainsBackupForbiddenKeys(backupWithIp))
    assertTrue(FleetRedaction.containsMeshAddress(backupWithIp))

    val cleanBackup = """{"kind":"UNIFIED_BACKUP","payload":{"controlUrl":"https://hs.example"}}"""
    assertFalse(FleetRedaction.jsonContainsBackupForbiddenKeys(cleanBackup))
    assertFalse(FleetRedaction.containsMeshAddress(cleanBackup))

    // Keys are checked after JSON parsing: escaped and nested keys are caught,
    // and a forbidden word used as a value is not a false positive.
    assertTrue(FleetRedaction.jsonContainsBackupForbiddenKeys("""{"payload":{"IP\u0073":[]}}"""))
    assertTrue(FleetRedaction.jsonContainsBackupForbiddenKeys("""{"a":[{"ipv6":"x"}]}"""))
    assertFalse(FleetRedaction.jsonContainsBackupForbiddenKeys("""{"note":"ips","list":["ipv4"]}"""))
  }

  @Test
  fun browserStillNeedsExternalControlForReads() {
    assertCat("external_control_disabled", call(req("mesh.status"), CallerScope.READ_STATUS_IP, enabled = false))
  }
}
