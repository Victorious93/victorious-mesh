package com.tailscale.ipn.fleet

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FleetNodeStatusTest {
  private val node = "mesh-0123456789abcdef0123456789abcdef"
  private val other = "mesh-ffffffffffffffffffffffffffffffff"
  private val src = "ai.droidcommand.app"
  private val now0 = 1_700_000_000_000L

  private class FakeBackend(
      var connected: Boolean = true,
      var backendState: String = "Running",
      var consent: Boolean = true,
      var throwOnStatus: Boolean = false,
      var wireGuard: Boolean = false,
  ) : MeshBackend {
    override fun wireGuardModeActive() = wireGuard

    override fun status(): MeshStatus {
      if (throwOnStatus) throw RuntimeException("boom /secret/path")
      return MeshStatus(
          backendState,
          connected,
          "https://controlplane.tailscale.com",
          "100.64.0.1",
          "fd7a:115c:a1e0::1",
      )
    }

    override fun vpnConsentGranted() = consent

    override fun up() {}

    override fun down() {}
  }

  private class RecordingSink : ConfirmationSink {
    val pending = ArrayList<PendingConfirmation>()

    override fun onConfirmationRequired(pending: PendingConfirmation) {
      this.pending.add(pending)
    }
  }

  private inner class Harness(
      val backend: FakeBackend = FakeBackend(),
      val clock: java.util.concurrent.atomic.AtomicLong =
          java.util.concurrent.atomic.AtomicLong(now0),
      val nonceCache: NonceCache = NonceCache(),
  ) {
    val dispatcher =
        FleetDispatcher(
            node,
            backend,
            clock = { clock.get() },
            nonceCache = nonceCache,
        )

    fun call(
        raw: String?,
        allowed: Boolean = true,
        enabled: Boolean = true,
        source: String = src,
    ) = dispatcher.handle(raw, allowed, enabled, source)
  }

  private var nonceSeq = 0

  private fun nextNonce(): String = (++nonceSeq).toString(16).padStart(32, '0')

  private fun canonical(
      nonce: String = nextNonce(),
      timestampMs: Long = now0,
      ttlMs: Long? = 120000,
      extraParams: String = "",
      args: String? = null,
      targetNodeRef: String? = node,
      targetNodeId: String = node,
  ): String {
    val fields = ArrayList<String>()
    fields.add("\"nonce\":\"$nonce\"")
    fields.add("\"timestampMs\":$timestampMs")
    if (ttlMs != null) fields.add("\"ttlMs\":$ttlMs")
    fields.add("\"requestId\":\"dca-1\"")
    if (targetNodeRef != null) fields.add("\"targetNodeRef\":\"$targetNodeRef\"")
    fields.add("\"destructive\":false")
    if (args != null) fields.add("\"args\":$args")
    if (extraParams.isNotEmpty()) fields.add(extraParams)
    return """{"protocol_major":1,"request_id":"dca-1","target_node_id":"$targetNodeId",
      |"operation":"node.status.get","timeout_ms":10000,"parameters":{${fields.joinToString(",")}}}"""
        .trimMargin()
        .replace("\n", "")
  }

  private fun env(
      op: String,
      actionRef: String = "",
      nonce: String = nextNonce(),
      timestampMs: Long = now0,
      params: String = "{}",
      target: String = node,
  ): String =
      """{"protocol_major":1,"protocol_minor":1,"op":"$op","requestId":"dca-1",
        |"targetNodeRef":"$target","actionRef":"$actionRef","params":$params,
        |"nonce":"$nonce","timestampMs":$timestampMs,"ttlMs":120000,"destructive":false}"""
          .trimMargin()
          .replace("\n", "")

  private fun assertCat(cat: String, r: BridgeResponse) {
    assertFalse(r.ok)
    assertEquals(cat, r.error?.category)
  }

  private fun domainMap(result: JsonObject): Map<String, JsonObject> =
      result["domains"]!!.jsonArray.associate {
        val o = it.jsonObject
        o["name"]!!.jsonPrimitive.content to o
      }

  @Test
  fun snapshotShapeViaCanonicalBridgeRequest() {
    val r = Harness().call(canonical())
    assertTrue(r.ok)
    val snap = r.result!!
    assertEquals(node, snap["nodeId"]!!.jsonPrimitive.content)
    assertEquals("com.victorious.mesh", snap["packageName"]!!.jsonPrimitive.content)
    assertEquals("EXTERNAL_NODE", snap["connectionClass"]!!.jsonPrimitive.content)
    assertEquals(now0, snap["updatedAtMs"]!!.jsonPrimitive.long)
    val domains = domainMap(snap)
    assertEquals(setOf("mesh", "vpn", "node_health"), domains.keys)
    assertEquals("ok", domains.getValue("mesh")["state"]!!.jsonPrimitive.content)
    assertEquals("headscale up", domains.getValue("mesh")["detail"]!!.jsonPrimitive.content)
    assertEquals("ok", domains.getValue("vpn")["state"]!!.jsonPrimitive.content)
    assertEquals("ok", domains.getValue("node_health")["state"]!!.jsonPrimitive.content)
    assertNull(domains.getValue("node_health")["detail"])
    val decoded = NodeStatusSnapshotCodec.decode(snap)
    assertEquals(node, decoded.nodeId)
    assertEquals(3, decoded.domains.size)
  }

  @Test
  fun remoteCommandActionRefIsCompatibleAlias() {
    val r = Harness().call(env("remote.command", actionRef = "node.status.get"))
    assertTrue(r.ok)
    assertEquals(node, r.result!!["nodeId"]!!.jsonPrimitive.content)
    assertEquals("EXTERNAL_NODE", r.result!!["connectionClass"]!!.jsonPrimitive.content)
  }

  @Test
  fun detailCapRefusesOver200() {
    val over = "x".repeat(NodeStatusSnapshot.DETAIL_MAX_CHARS + 1)
    val snap =
        NodeStatusSnapshot(
            nodeId = node,
            packageName = "com.victorious.mesh",
            connectionClass = NodeStatusSnapshot.CONNECTION_EXTERNAL_NODE,
            updatedAtMs = now0,
            domains =
                listOf(StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.OK, over)),
        )
    try {
      NodeStatusSnapshotCodec.encode(snap)
      fail("expected invalid_parameters for detail over 200")
    } catch (e: InvalidParametersException) {
      assertTrue(e.message!!.contains("200"))
    }
  }

  @Test
  fun detailAt200IsAccepted() {
    val exact = "x".repeat(NodeStatusSnapshot.DETAIL_MAX_CHARS)
    val snap =
        NodeStatusSnapshot(
            nodeId = node,
            packageName = "com.victorious.mesh",
            connectionClass = NodeStatusSnapshot.CONNECTION_EXTERNAL_NODE,
            updatedAtMs = now0,
            domains =
                listOf(StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.OK, exact)),
        )
    val encoded = NodeStatusSnapshotCodec.encode(snap)
    assertEquals(exact, encoded["domains"]!!.jsonArray[0].jsonObject["detail"]!!.jsonPrimitive.content)
  }

  @Test
  fun detailRedactionRefusesSecretsAndIps() {
    val secrets =
        listOf(
            "password leaked",
            "apiKey present",
            "api_token=abc",
            "bearer token",
            "has a secret",
            "codeWord alpha",
            "whitelist dump",
            "signingKey material",
            "privateKey xyz",
            "ip 100.64.0.1 leaked",
            "v6 fd7a:115c:a1e0::1",
        )
    for (detail in secrets) {
      try {
        NodeStatusSnapshotCodec.validateDetail(detail)
        fail("expected refuse for: $detail")
      } catch (e: InvalidParametersException) {
        assertTrue(e.message!!.contains("forbidden"))
      }
    }
    NodeStatusSnapshotCodec.validateDetail("headscale up")
    NodeStatusSnapshotCodec.validateDetail(null)
  }

  @Test
  fun downServiceStillAnswersOk() {
    val r =
        Harness(FakeBackend(connected = false, backendState = "Stopped")).call(canonical())
    assertTrue(r.ok)
    val domains = domainMap(r.result!!)
    assertEquals("down", domains.getValue("mesh")["state"]!!.jsonPrimitive.content)
    assertEquals("down", domains.getValue("vpn")["state"]!!.jsonPrimitive.content)
    assertEquals("ok", domains.getValue("node_health")["state"]!!.jsonPrimitive.content)
  }

  @Test
  fun statusReadFailureStillAnswersOk() {
    val r =
        Harness(FakeBackend(throwOnStatus = true)).call(canonical())
    assertTrue(r.ok)
    val domains = domainMap(r.result!!)
    assertEquals("unknown", domains.getValue("mesh")["state"]!!.jsonPrimitive.content)
    assertEquals("degraded", domains.getValue("node_health")["state"]!!.jsonPrimitive.content)
    val detail = domains.getValue("mesh")["detail"]!!.jsonPrimitive.content
    assertFalse(detail.contains("secret"))
    assertFalse(detail.contains("/"))
  }

  @Test
  fun wireGuardModeMeshDownVpnUp() {
    val r =
        Harness(FakeBackend(wireGuard = true)).call(canonical())
    assertTrue(r.ok)
    val domains = domainMap(r.result!!)
    assertEquals("down", domains.getValue("mesh")["state"]!!.jsonPrimitive.content)
    assertEquals("WireGuard mode", domains.getValue("mesh")["detail"]!!.jsonPrimitive.content)
    assertEquals("ok", domains.getValue("vpn")["state"]!!.jsonPrimitive.content)
    assertEquals("WireGuard up", domains.getValue("vpn")["detail"]!!.jsonPrimitive.content)
  }

  @Test
  fun capabilitiesGetListsNodeStatus() {
    val r = Harness().call(env("capabilities.get"))
    assertTrue(r.ok)
    val byName =
        r.result!!["operations"]!!.jsonArray.associate {
          it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
        }
    val cap = byName.getValue("node.status.get")
    assertEquals("read", cap["kind"]!!.jsonPrimitive.content)
    assertTrue(cap["remoteAllowed"]!!.jsonPrimitive.boolean)
    assertFalse(cap["requiresConfirm"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun unauthorizedCallerRejected() {
    val r = Harness().call(canonical(), allowed = false)
    assertCat("unauthorized_caller", r)
    assertNull(r.nodeId)
  }

  @Test
  fun replayDetectedOnDuplicateNonce() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(canonical(nonce = nonce)).ok)
    assertCat("replay_detected", h.call(canonical(nonce = nonce)))
  }

  @Test
  fun expiredEnvelopeRejected() {
    val r = Harness().call(canonical(timestampMs = now0 - 200_000))
    assertCat("expired", r)
  }

  @Test
  fun aliasStillChecksReplay() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(env("remote.command", actionRef = "node.status.get", nonce = nonce)).ok)
    assertCat(
        "replay_detected",
        h.call(env("remote.command", actionRef = "node.status.get", nonce = nonce)),
    )
  }

  @Test
  fun argsNodeIdMismatchIsNotFound() {
    val r = Harness().call(canonical(args = """{"nodeId":"$other"}"""))
    assertCat("not_found", r)
  }

  @Test
  fun argsNodeIdMalformedIsInvalidParameters() {
    val r = Harness().call(canonical(args = """{"nodeId":"not-a-node"}"""))
    assertCat("invalid_parameters", r)
  }

  @Test
  fun argsNodeIdThisNodeSucceeds() {
    val r = Harness().call(canonical(args = """{"nodeId":"$node"}"""))
    assertTrue(r.ok)
    assertEquals(node, r.result!!["nodeId"]!!.jsonPrimitive.content)
  }

  @Test
  fun unknownParamKeysAreIgnored() {
    val r =
        Harness()
            .call(
                canonical(
                    extraParams = """"futureField":{"x":1},"trigger":{"autoApprove":true}""",
                    args = """{"nodeId":"$node","extra":true}""",
                )
            )
    assertTrue(r.ok)
    assertEquals(node, r.result!!["nodeId"]!!.jsonPrimitive.content)
  }

  @Test
  fun missingNonceIsInvalidParameters() {
    val raw =
        """{"protocol_major":1,"request_id":"dca-1","target_node_id":"$node",
          |"operation":"node.status.get","timeout_ms":10000,"parameters":{"timestampMs":$now0}}"""
            .trimMargin()
            .replace("\n", "")
    assertCat("invalid_parameters", Harness().call(raw))
  }

  @Test
  fun externalControlDisabledLikeOtherReadOps() {
    assertCat("external_control_disabled", Harness().call(canonical(), enabled = false))
  }

  @Test
  fun canonicalPathNeverRequestsConfirmation() {
    val sink = RecordingSink()
    val dispatcher =
        FleetDispatcher(
            node,
            FakeBackend(),
            clock = { now0 },
            confirmationSink = sink,
            capabilities =
                listOf(
                    RemoteCapability(
                        FleetProtocol.OP_NODE_STATUS,
                        FleetProtocol.KIND_DESTRUCTIVE,
                        remoteAllowed = true,
                        requiresConfirm = true,
                    )
                ),
        )
    val r = dispatcher.handle(canonical(), callerAllowed = true, controlEnabled = true, sourceNodeRef = src)
    assertTrue(r.ok)
    assertTrue(sink.pending.isEmpty())
  }

  @Test
  fun aliasSkipsConfirmationGating() {
    val sink = RecordingSink()
    val dispatcher =
        FleetDispatcher(
            node,
            FakeBackend(),
            clock = { now0 },
            confirmationSink = sink,
            capabilities =
                listOf(
                    RemoteCapability(
                        FleetProtocol.OP_NODE_STATUS,
                        FleetProtocol.KIND_DESTRUCTIVE,
                        remoteAllowed = true,
                        requiresConfirm = true,
                    )
                ),
        )
    val r =
        dispatcher.handle(
            env("remote.command", actionRef = "node.status.get"),
            callerAllowed = true,
            controlEnabled = true,
            sourceNodeRef = src,
        )
    assertTrue(r.ok)
    assertTrue(sink.pending.isEmpty())
  }

  @Test
  fun producedSnapshotHasNoIpsOrSecrets() {
    val r = Harness().call(canonical())
    val text = r.result.toString()
    assertFalse(text.contains("100.64.0.1"))
    assertFalse(text.contains("fd7a"))
    assertFalse(text.contains("password", ignoreCase = true))
    assertFalse(text.contains("privateKey", ignoreCase = true))
  }
}
