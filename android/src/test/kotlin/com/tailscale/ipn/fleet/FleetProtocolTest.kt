package com.tailscale.ipn.fleet

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetProtocolTest {
  private val node = "mesh-0123456789abcdef0123456789abcdef"

  private class FakeBackend(
      var consent: Boolean = true,
      var throwOnStatus: Boolean = false,
      var wireGuard: Boolean = false,
  ) : MeshBackend {
    override fun wireGuardModeActive() = wireGuard

    var ups = 0
    var downs = 0

    override fun status(): MeshStatus {
      if (throwOnStatus) throw RuntimeException("boom /secret/path")
      return MeshStatus("Running", true, "https://controlplane.tailscale.com", "100.64.0.1", "fd7a:115c:a1e0::1")
    }

    override fun vpnConsentGranted() = consent

    override fun up() {
      ups++
    }

    override fun down() {
      downs++
    }
  }

  private fun req(op: String, major: Int = 1, target: String = node, params: String = "{}") =
      """{"protocol_major":$major,"request_id":"dca-1","target_node_id":"$target","operation":"$op","timeout_ms":10000,"parameters":$params,"extra_field":42}"""

  private fun call(raw: String?, be: MeshBackend = FakeBackend(), allowed: Boolean = true, enabled: Boolean = true) =
      FleetDispatcher(node, be).handle(raw, allowed, enabled)

  // --- envelope parsing ---
  @Test
  fun parsesRequestWithDefaultsAndUnknownKeys() {
    val r =
        FleetProtocol.json.decodeFromString(
            BridgeRequest.serializer(),
            """{"request_id":"dca-x","target_node_id":"n","operation":"mesh.status","zzz":1}""",
        )
    assertEquals(1, r.protocolMajor)
    assertEquals(10000, r.timeoutMs)
    assertTrue(r.parameters.isEmpty())
  }

  @Test
  fun responseEncodesSnakeCaseAndDefaults() {
    val s =
        FleetProtocol.json.encodeToString(
            BridgeResponse.serializer(),
            BridgeResponse(requestId = "dca-1", nodeId = node, ok = false, error = BridgeError("internal", "x")),
        )
    assertTrue(s.contains("\"protocol_major\":1"))
    assertTrue(s.contains("\"protocol_minor\":1"))
    assertTrue(s.contains("\"request_id\":\"dca-1\""))
    assertTrue(s.contains("\"node_id\":\"$node\""))
    assertTrue(s.contains("\"category\":\"internal\""))
  }

  @Test fun malformedJson() = assertCat("malformed_request", call("{not json"))

  @Test fun nullRequest() = assertCat("malformed_request", call(null))

  @Test fun missingRequiredField() = assertCat("malformed_request", call("""{"operation":"mesh.status"}"""))

  // --- protocol major ---
  @Test
  fun protocolMismatch() {
    val r = call(req("mesh.status", major = 2))
    assertCat("protocol_mismatch", r)
    assertEquals("dca-1", r.requestId)
  }

  @Test fun protocolMajorZeroRejected() = assertCat("protocol_mismatch", call(req("mesh.status", major = 0)))

  // --- node id ---
  @Test
  fun nodeIdFormat() {
    repeat(50) {
      val id = FleetProtocol.newNodeId()
      assertTrue(id, Regex("^mesh-[0-9a-f]{32}$").matches(id))
      assertTrue(FleetProtocol.isValidNodeId(id))
    }
    assertNotEquals(FleetProtocol.newNodeId(), FleetProtocol.newNodeId())
    assertFalse(FleetProtocol.isValidNodeId("mesh-0123456789ABCDEF0123456789abcdef"))
    assertFalse(FleetProtocol.isValidNodeId("node-0123456789abcdef0123456789abcdef"))
    assertFalse(FleetProtocol.isValidNodeId("mesh-0123"))
  }

  @Test fun nodeMismatch() = assertCat("node_mismatch", call(req("mesh.status", target = "mesh-ffffffffffffffffffffffffffffffff")))

  // --- dispatch ---
  @Test
  fun capabilities() {
    val r = call(req("mesh.capabilities.get"))
    assertTrue(r.ok)
    assertEquals(node, r.nodeId)
    assertEquals("dca-1", r.requestId)
    val res = r.result!!
    assertEquals(FleetProtocol.OPERATIONS, res["operations"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertFalse(res["dca_required"]!!.jsonPrimitive.boolean)
    assertEquals("external_node", res["fleet_class"]!!.jsonPrimitive.content)
    assertEquals(1, res["protocol_minor"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun capabilitiesAllowedWhenControlDisabled() {
    val r = call(req("mesh.capabilities.get"), enabled = false)
    assertTrue(r.ok)
    assertEquals(FleetProtocol.OPERATIONS, r.result!!["operations"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun status() {
    val r = call(req("mesh.status"))
    assertTrue(r.ok)
    assertEquals("Running", r.result!!["backend_state"]!!.jsonPrimitive.content)
    assertTrue(r.result!!["connected"]!!.jsonPrimitive.boolean)
    assertEquals(2, r.result!!["ips"]!!.jsonArray.size)
  }

  @Test
  fun ipGet() {
    val r = call(req("mesh.ip.get"))
    assertEquals(JsonPrimitive("100.64.0.1"), r.result!!["ipv4"])
    assertEquals(JsonPrimitive("fd7a:115c:a1e0::1"), r.result!!["ipv6"])
  }

  @Test
  fun upAndDown() {
    val be = FakeBackend()
    assertTrue(call(req("mesh.up"), be).ok)
    assertTrue(call(req("mesh.down"), be).ok)
    assertEquals(1, be.ups)
    assertEquals(1, be.downs)
  }

  @Test
  fun upWithoutConsentIsSafetyRefused() {
    val be = FakeBackend(consent = false)
    val r = call(req("mesh.up"), be)
    assertCat("safety_refused", r)
    assertTrue(r.error!!.message.contains("open", ignoreCase = true))
    assertEquals(0, be.ups)
  }

  @Test
  fun statusReportsMeshDownInWireGuardMode() {
    val r = call(req("mesh.status"), FakeBackend(wireGuard = true))
    assertTrue(r.ok)
    assertFalse(r.result!!["connected"]!!.jsonPrimitive.boolean)
    assertEquals("Stopped", r.result!!["backend_state"]!!.jsonPrimitive.content)
    assertEquals(0, r.result!!["ips"]!!.jsonArray.size)
  }

  @Test
  fun ipGetIsEmptyInWireGuardMode() {
    val r = call(req("mesh.ip.get"), FakeBackend(wireGuard = true))
    assertTrue(r.ok)
    assertEquals(JsonNull, r.result!!["ipv4"])
  }

  @Test
  fun upIsRefusedInWireGuardMode() {
    val be = FakeBackend(wireGuard = true)
    assertCat("safety_refused", call(req("mesh.up"), be))
    assertEquals(0, be.ups)
  }

  @Test
  fun downDoesNotStopWireGuardTunnel() {
    val be = FakeBackend(wireGuard = true)
    assertTrue(call(req("mesh.down"), be).ok)
    assertEquals(0, be.downs)
  }

  @Test
  fun forTunnelModeIsIdentityInMeshMode() {
    val s = MeshStatus("Running", true, null, "100.64.0.1", null)
    assertEquals(s, s.forTunnelMode(false))
  }

  @Test fun unsupportedOperation() = assertCat("unsupported_operation", call(req("mesh.exit_node.set")))

  @Test fun invalidParameters() = assertCat("invalid_parameters", call(req("mesh.status", params = """{"x":1}""")))

  @Test
  fun invalidParametersWhenParametersNotObject() =
      assertCat("malformed_request", call(req("mesh.status", params = "[1]")))

  @Test
  fun internalErrorDoesNotLeakMessage() {
    val r = call(req("mesh.status"), FakeBackend(throwOnStatus = true))
    assertCat("internal", r)
    assertFalse(r.error!!.message.contains("secret"))
  }

  @Test
  fun unauthorizedCallerCheckedFirst() {
    val r = call("garbage", allowed = false, enabled = false)
    assertCat("unauthorized_caller", r)
    assertNull(r.nodeId)
  }

  @Test fun disabledControl() = assertCat("external_control_disabled", call(req("mesh.status"), enabled = false))

  @Test
  fun splitAddresses() {
    assertEquals("100.1.2.3" to "fd7a::5", FleetDispatcher.splitAddresses(listOf("fd7a::5/128", "100.1.2.3/32")))
    assertEquals(null to null, FleetDispatcher.splitAddresses(null))
  }

  @Test
  fun responsesCarryNoCredentialKeys() {
    for (op in FleetProtocol.OPERATIONS) {
      val raw =
          if (op == FleetProtocol.OP_NODE_STATUS ||
              op == FleetProtocol.OP_CONFIG_EXPORT ||
              op == FleetProtocol.OP_CONFIG_IMPORT)
              req(
                  op,
                  params =
                      """{"nonce":"0123456789abcdef0123456789abcdef","timestampMs":1700000000000}""",
              )
          else req(op)
      val s = FleetProtocol.json.encodeToString(BridgeResponse.serializer(), call(raw)).lowercase()
      for (bad in listOf("key", "auth", "token", "secret", "password", "credential")) {
        assertFalse("$op response contains '$bad': $s", s.contains("\"$bad") || s.contains("_$bad"))
      }
    }
  }

  // --- allowlist ---
  private val dca = "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"

  @Test
  fun allowlistMatches() {
    assertTrue(CallerAllowlist.isAllowed(mapOf("ai.droidcommand.app" to listOf(CallerAllowlist.normalize(dca)))))
    assertTrue(CallerAllowlist.isAllowed(mapOf("ai.droidcommand.app.debug" to listOf(dca))))
    assertTrue(CallerAllowlist.isAllowed(mapOf("other.pkg" to listOf(dca), "ai.droidcommand.app" to listOf("00", dca))))
  }

  private val rel = "9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D"

  @Test
  fun allowlistPinsBothCertsForBothPackages() {
    for (pkg in listOf("ai.droidcommand.app", "ai.droidcommand.app.debug")) {
      assertTrue(CallerAllowlist.isAllowed(mapOf(pkg to listOf(dca))))
      assertTrue(CallerAllowlist.isAllowed(mapOf(pkg to listOf(rel))))
      assertTrue(CallerAllowlist.isAllowed(mapOf(pkg to listOf(CallerAllowlist.normalize(rel)))))
      assertEquals(setOf(CallerAllowlist.normalize(dca), CallerAllowlist.normalize(rel)), CallerAllowlist.ENTRIES[pkg])
    }
    assertEquals(2, CallerAllowlist.ENTRIES.size)
    assertFalse(CallerAllowlist.isAllowed(mapOf("evil.app" to listOf(rel))))
  }

  @Test
  fun allowlistRejects() {
    assertFalse(CallerAllowlist.isAllowed(mapOf("evil.app" to listOf(dca))))
    assertFalse(CallerAllowlist.isAllowed(mapOf("ai.droidcommand.app" to listOf("00".repeat(32)))))
    assertFalse(CallerAllowlist.isAllowed(mapOf("ai.droidcommand.app" to emptyList())))
    assertFalse(CallerAllowlist.isAllowed(emptyMap()))
  }

  @Test
  fun sha256Hex() =
      assertEquals(
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
          CallerAllowlist.sha256Hex(ByteArray(0)),
      )

  private fun assertCat(cat: String, r: BridgeResponse) {
    assertFalse(r.ok)
    assertNull(r.result)
    assertEquals(cat, r.error?.category)
  }
}
