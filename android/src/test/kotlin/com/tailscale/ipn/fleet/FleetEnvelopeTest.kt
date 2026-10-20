package com.tailscale.ipn.fleet

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetEnvelopeTest {
  private val node = "mesh-0123456789abcdef0123456789abcdef"
  private val other = "mesh-ffffffffffffffffffffffffffffffff"
  private val src = "ai.droidcommand.app"
  private val now0 = 1_700_000_000_000L

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

  private class RecordingSink : ConfirmationSink {
    val pending = ArrayList<PendingConfirmation>()

    override fun onConfirmationRequired(pending: PendingConfirmation) {
      this.pending.add(pending)
    }
  }

  private inner class Harness(
      val backend: FakeBackend = FakeBackend(),
      val clock: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(now0),
      val nonceCache: NonceCache = NonceCache(),
      val confirmations: ConfirmationRegistry = ConfirmationRegistry(),
      val sink: RecordingSink = RecordingSink(),
      val capabilities: List<RemoteCapability> = RemoteCapability.DEFAULTS,
      val knownNodeRefs: Set<String>? = null,
      val pairing: NodePairing = NodePairing { _, target -> target == node },
  ) {
    val dispatcher =
        FleetDispatcher(
            node,
            backend,
            clock = { clock.get() },
            nonceCache = nonceCache,
            confirmations = confirmations,
            confirmationSink = sink,
            capabilities = capabilities,
            knownNodeRefs = knownNodeRefs,
            pairing = pairing,
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

  private fun env(
      op: String,
      actionRef: String = "",
      requestId: String = "dca-1",
      target: String = node,
      nonce: String = nextNonce(),
      timestampMs: Long = now0,
      ttlMs: Long? = null,
      params: String = "{}",
      destructive: Boolean = false,
      trigger: String? = null,
      extra: String = "",
      protocolMajor: Int = 1,
      macroId: String? = null,
      stepIndex: Int? = null,
      onError: String? = null,
      steps: String? = null,
      dryRun: Boolean? = null,
  ): String {
    val parts = ArrayList<String>()
    parts.add("\"protocol_major\":$protocolMajor")
    parts.add("\"protocol_minor\":1")
    parts.add("\"op\":\"$op\"")
    parts.add("\"requestId\":\"$requestId\"")
    parts.add("\"targetNodeRef\":\"$target\"")
    parts.add("\"actionRef\":\"$actionRef\"")
    parts.add("\"params\":$params")
    parts.add("\"nonce\":\"$nonce\"")
    parts.add("\"timestampMs\":$timestampMs")
    if (ttlMs != null) parts.add("\"ttlMs\":$ttlMs")
    parts.add("\"destructive\":$destructive")
    if (trigger != null) parts.add("\"trigger\":$trigger")
    if (macroId != null) parts.add("\"macroId\":\"$macroId\"")
    if (stepIndex != null) parts.add("\"stepIndex\":$stepIndex")
    if (onError != null) parts.add("\"onError\":\"$onError\"")
    if (steps != null) parts.add("\"steps\":$steps")
    if (dryRun != null) parts.add("\"dryRun\":$dryRun")
    if (extra.isNotEmpty()) parts.add(extra)
    return "{${parts.joinToString(",")}}"
  }

  private fun assertCat(cat: String, r: BridgeResponse) {
    assertFalse(r.ok)
    assertEquals(cat, r.error?.category)
  }

  // --- capabilities.get shape ---
  @Test
  fun capabilitiesGetReturnsPerOpMetadata() {
    val h = Harness()
    val r = h.call(env("capabilities.get"))
    assertTrue(r.ok)
    assertEquals(1, r.protocolMinor)
    val ops = r.result!!["operations"]!!.jsonArray
    assertEquals(5, ops.size)
    val byName = ops.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }
    fun cap(name: String, kind: String, confirm: Boolean) {
      val o = byName.getValue(name)
      assertEquals(kind, o["kind"]!!.jsonPrimitive.content)
      assertTrue(o["remoteAllowed"]!!.jsonPrimitive.boolean)
      assertEquals(confirm, o["requiresConfirm"]!!.jsonPrimitive.boolean)
    }
    cap("mesh.capabilities.get", "read", false)
    cap("mesh.status", "read", false)
    cap("mesh.ip.get", "read", false)
    cap("mesh.up", "action", false)
    cap("mesh.down", "destructive", true)
    assertEquals(1, r.result!!["protocol_minor"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun capabilitiesGetAllowedWhenControlDisabled() {
    val r = Harness().call(env("capabilities.get"), enabled = false)
    assertTrue(r.ok)
  }

  // --- error paths ---
  @Test
  fun unauthorizedIgnoresTrigger() {
    val r =
        Harness()
            .call(
                env("remote.command", actionRef = "mesh.status", trigger = """{"authorized":true,"role":"root"}"""),
                allowed = false,
            )
    assertCat("unauthorized", r)
    assertNull(r.nodeId)
  }

  @Test
  fun unauthorizedCallerStillUsedForLegacyGarbage() {
    val r = Harness().call("{not json", allowed = false)
    assertCat("unauthorized_caller", r)
  }

  @Test
  fun invalidParametersOnMalformedEnvelope() {
    assertCat("invalid_parameters", Harness().call("""{"op":"capabilities.get"}"""))
  }

  @Test
  fun invalidParametersOnMissingNonce() {
    val raw =
        """{"op":"capabilities.get","requestId":"dca-1","targetNodeRef":"$node","timestampMs":$now0}"""
    assertCat("invalid_parameters", Harness().call(raw))
  }

  @Test
  fun invalidParametersOnBadNonce() {
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", nonce = "short")))
  }

  @Test
  fun invalidParametersOnZeroTtl() {
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", ttlMs = 0)))
  }

  @Test
  fun invalidParametersOnNegativeTtl() {
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", ttlMs = -5)))
  }

  @Test
  fun invalidParametersOnMissingTarget() {
    val raw =
        """{"op":"capabilities.get","requestId":"dca-1","targetNodeRef":"","nonce":"${nextNonce()}","timestampMs":$now0}"""
    assertCat("invalid_parameters", Harness().call(raw))
  }

  @Test
  fun invalidParametersOnMalformedTarget() {
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", target = "mesh-0123")))
    assertCat(
        "invalid_parameters",
        Harness().call(env("capabilities.get", target = "mesh-0123456789ABCDEF0123456789ABCDEF")),
    )
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", target = "not-a-node")))
  }

  @Test
  fun invalidParametersOnBlankActionRef() {
    assertCat("invalid_parameters", Harness().call(env("remote.command", actionRef = "")))
  }

  @Test
  fun invalidParametersOnNonEmptyParams() {
    assertCat("invalid_parameters", Harness().call(env("remote.command", actionRef = "mesh.status", params = """{"x":1}""")))
  }

  @Test
  fun invalidParametersOnWrongProtocolMajor() {
    assertCat("invalid_parameters", Harness().call(env("capabilities.get", protocolMajor = 2)))
  }

  @Test
  fun notFoundOnUnknownTarget() {
    val r = Harness().call(env("remote.command", actionRef = "mesh.status", target = other))
    assertCat("not_found", r)
    assertEquals(node, r.nodeId)
  }

  @Test
  fun notPairedWhenKnownNodeNotPairedWithSender() {
    val r =
        Harness(pairing = NodePairing { _, _ -> false })
            .call(env("remote.command", actionRef = "mesh.status"))
    assertCat("not_paired", r)
    assertEquals(node, r.nodeId)
  }

  @Test
  fun notFoundOnUnknownAction() {
    assertCat("not_found", Harness().call(env("remote.command", actionRef = "mesh.exit_node.set")))
  }

  @Test
  fun unsupportedOp() {
    assertCat("unsupported_op", Harness().call(env("fleet.dance")))
  }

  @Test
  fun internalDoesNotLeak() {
    val r = Harness(FakeBackend(throwOnStatus = true)).call(env("remote.command", actionRef = "mesh.status"))
    assertCat("internal", r)
    assertFalse(r.error!!.message.contains("secret"))
  }

  @Test
  fun externalControlDisabledExceptCapabilities() {
    val h = Harness()
    assertCat("external_control_disabled", h.call(env("remote.command", actionRef = "mesh.status"), enabled = false))
    assertTrue(h.call(env("capabilities.get"), enabled = false).ok)
  }

  @Test
  fun triggerCannotEnableExternalControl() {
    val r =
        Harness()
            .call(
                env(
                    "remote.command",
                    actionRef = "mesh.status",
                    trigger = """{"force":true,"externalControl":true}""",
                ),
                enabled = false,
            )
    assertCat("external_control_disabled", r)
  }

  @Test
  fun capabilityOff() {
    val caps =
        RemoteCapability.DEFAULTS +
            RemoteCapability("mesh.exit_node.set", FleetProtocol.KIND_ACTION, remoteAllowed = false, requiresConfirm = false)
    val r = Harness(capabilities = caps).call(env("remote.command", actionRef = "mesh.exit_node.set"))
    assertCat("capability_off", r)
  }

  // --- replay / expiry / skew ---
  @Test
  fun expiredByTtl() {
    val h = Harness()
    val ts = now0 - 80_000
    assertCat("expired", h.call(env("capabilities.get", timestampMs = ts, ttlMs = 30_000)))
  }

  @Test
  fun fullThreeHundredSecondTtlIsUsable() {
    val h = Harness()
    assertTrue(h.call(env("capabilities.get", timestampMs = now0 - 250_000, ttlMs = 300_000)).ok)
    assertTrue(h.call(env("capabilities.get", timestampMs = now0 - 130_000, ttlMs = 300_000)).ok)
  }

  @Test
  fun expiredWhenNowPastTimestampPlusTtl() {
    val h = Harness()
    assertCat("expired", h.call(env("capabilities.get", timestampMs = now0 - 300_001, ttlMs = 300_000)))
  }

  @Test
  fun expiredByFutureSkewOnly() {
    val h = Harness()
    assertCat("expired", h.call(env("capabilities.get", timestampMs = now0 + 130_000)))
    assertTrue(h.call(env("capabilities.get", timestampMs = now0 + 119_000)).ok)
  }

  @Test
  fun withinTtlSucceeds() {
    val h = Harness()
    assertTrue(h.call(env("capabilities.get", timestampMs = now0 - 10_000, ttlMs = 60_000)).ok)
  }

  @Test
  fun ttlAboveMaxIsClampedNotRejected() {
    assertEquals(300_000L, FleetProtocol.clampTtl(400_000))
    val h = Harness()
    assertTrue(h.call(env("capabilities.get", ttlMs = 400_000)).ok)
    h.clock.set(now0 + 300_001)
    assertCat("expired", h.call(env("capabilities.get", timestampMs = now0, ttlMs = 400_000, nonce = nextNonce())))
  }

  @Test
  fun defaultTtlIsTwoMinutes() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = nonce, timestampMs = now0)).ok)
    h.clock.set(now0 + 120_001)
    assertCat("expired", h.call(env("capabilities.get", timestampMs = now0, nonce = nextNonce())))
  }

  @Test
  fun isExpiredAndFutureSkewHelpers() {
    assertFalse(FleetDispatcher.isExpired(now0, 300_000, now0 + 250_000))
    assertTrue(FleetDispatcher.isExpired(now0, 300_000, now0 + 300_001))
    assertFalse(FleetDispatcher.isFutureSkew(now0 + 120_000, now0))
    assertTrue(FleetDispatcher.isFutureSkew(now0 + 120_001, now0))
  }

  @Test
  fun replayDetectedOnDuplicateNonce() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = nonce)).ok)
    assertCat("replay_detected", h.call(env("capabilities.get", nonce = nonce)))
  }

  @Test
  fun triggerCannotBypassReplay() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = nonce)).ok)
    assertCat(
        "replay_detected",
        h.call(env("capabilities.get", nonce = nonce, trigger = """{"replayOk":true}""")),
    )
  }

  @Test
  fun sameNonceDifferentCallerIsNotReplay() {
    val cache = NonceCache()
    val h = Harness(nonceCache = cache)
    val nonce = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = nonce), source = "pkg.a").ok)
    assertTrue(h.call(env("capabilities.get", nonce = nonce), source = "pkg.b").ok)
  }

  @Test
  fun nonceCacheExpiresAfterTenMinutes() {
    val cache = NonceCache(minRetainMs = FleetProtocol.NONCE_RETAIN_MS, maxEntries = 16)
    val h = Harness(nonceCache = cache)
    val nonce = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = nonce)).ok)
    h.clock.addAndGet(FleetProtocol.NONCE_RETAIN_MS - 1)
    assertCat("replay_detected", h.call(env("remote.command", actionRef = "mesh.status", nonce = nonce, timestampMs = h.clock.get())))
    h.clock.addAndGet(2)
    assertTrue(h.call(env("capabilities.get", nonce = nonce, timestampMs = h.clock.get())).ok)
  }

  @Test
  fun nonceCacheEvictsOldestWhenBounded() {
    val cache = NonceCache(minRetainMs = FleetProtocol.NONCE_RETAIN_MS, maxEntries = 2)
    val h = Harness(nonceCache = cache)
    val n1 = nextNonce()
    val n2 = nextNonce()
    val n3 = nextNonce()
    assertTrue(h.call(env("capabilities.get", nonce = n1)).ok)
    assertTrue(h.call(env("capabilities.get", nonce = n2)).ok)
    assertTrue(h.call(env("capabilities.get", nonce = n3)).ok)
    assertEquals(2, cache.size())
    assertTrue(h.call(env("capabilities.get", nonce = n1)).ok)
    assertCat("replay_detected", h.call(env("capabilities.get", nonce = n3)))
  }

  // --- remote.command + WireGuard ---
  @Test
  fun remoteCommandStatus() {
    val r = Harness().call(env("remote.command", actionRef = "mesh.status"))
    assertTrue(r.ok)
    assertEquals("Running", r.result!!["backend_state"]!!.jsonPrimitive.content)
  }

  @Test
  fun remoteCommandUpRefusedInWireGuardMode() {
    val be = FakeBackend(wireGuard = true)
    val r = Harness(be).call(env("remote.command", actionRef = "mesh.up"))
    assertCat("safety_refused", r)
    assertEquals(0, be.ups)
  }

  @Test
  fun remoteCommandStatusReportsDownInWireGuardMode() {
    val r = Harness(FakeBackend(wireGuard = true)).call(env("remote.command", actionRef = "mesh.status"))
    assertTrue(r.ok)
    assertFalse(r.result!!["connected"]!!.jsonPrimitive.boolean)
  }

  // --- destructive gate ---
  @Test
  fun destructiveDownRequiresConfirmationAndDoesNotRun() {
    val h = Harness()
    val r = h.call(env("remote.command", actionRef = "mesh.down"))
    assertCat("confirmation_required", r)
    assertEquals(0, h.backend.downs)
    assertEquals(1, h.sink.pending.size)
    val confirmed = h.dispatcher.confirmAndExecute(h.sink.pending[0].id)
    assertTrue(confirmed.ok)
    assertEquals(1, h.backend.downs)
  }

  @Test
  fun triggerCannotAutoApproveDestructive() {
    val h = Harness()
    val r =
        h.call(
            env(
                "remote.command",
                actionRef = "mesh.down",
                destructive = false,
                trigger = """{"autoApprove":true,"source":"local","trusted":true}""",
            )
        )
    assertCat("confirmation_required", r)
    assertEquals(0, h.backend.downs)
  }

  @Test
  fun denyLeavesMeshRunning() {
    val h = Harness()
    assertCat("confirmation_required", h.call(env("remote.command", actionRef = "mesh.down")))
    h.dispatcher.denyConfirmation(h.sink.pending[0].id)
    assertCat("not_found", h.dispatcher.confirmAndExecute(h.sink.pending[0].id))
    assertEquals(0, h.backend.downs)
  }

  @Test
  fun confirmExpiredPending() {
    val h = Harness()
    assertCat("confirmation_required", h.call(env("remote.command", actionRef = "mesh.down", ttlMs = 60_000)))
    h.clock.addAndGet(60_001)
    assertCat("expired", h.dispatcher.confirmAndExecute(h.sink.pending[0].id))
    assertEquals(0, h.backend.downs)
  }

  @Test
  fun legacyMeshDownStillRunsImmediately() {
    val h = Harness()
    val raw =
        """{"protocol_major":1,"request_id":"dca-1","target_node_id":"$node","operation":"mesh.down","timeout_ms":10000,"parameters":{}}"""
    assertTrue(h.call(raw).ok)
    assertEquals(1, h.backend.downs)
    assertTrue(h.sink.pending.isEmpty())
  }

  @Test
  fun readAndActionOpsDoNotConfirm() {
    val h = Harness()
    assertTrue(h.call(env("remote.command", actionRef = "mesh.status")).ok)
    assertTrue(h.call(env("remote.command", actionRef = "mesh.ip.get")).ok)
    assertTrue(h.call(env("remote.command", actionRef = "mesh.up")).ok)
    assertEquals(1, h.backend.ups)
    assertTrue(h.sink.pending.isEmpty())
  }

  // --- macro.step ---
  @Test
  fun macroStepRunsAction() {
    val r =
        Harness()
            .call(env("macro.step", actionRef = "mesh.status", macroId = "m1", stepIndex = 0, onError = "abort"))
    assertTrue(r.ok)
    assertEquals("m1", r.result!!["macroId"]!!.jsonPrimitive.content)
    assertEquals(0, r.result!!["stepIndex"]!!.jsonPrimitive.content.toInt())
    assertTrue(r.result!!["ok"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun macroStepInvalidOnError() {
    assertCat(
        "invalid_parameters",
        Harness().call(env("macro.step", actionRef = "mesh.status", macroId = "m1", stepIndex = 0, onError = "skip")),
    )
  }

  @Test
  fun macroStepMissingMacroId() {
    assertCat("invalid_parameters", Harness().call(env("macro.step", actionRef = "mesh.status", stepIndex = 0)))
  }

  @Test
  fun macroStepDestructiveIsGated() {
    val h = Harness()
    val r = h.call(env("macro.step", actionRef = "mesh.down", macroId = "m1", stepIndex = 2, onError = "continue"))
    assertCat("confirmation_required", r)
    assertEquals(0, h.backend.downs)
    assertEquals(1, h.sink.pending.size)
  }

  // --- macro.run abort / continue / dryRun ---
  @Test
  fun macroRunAbortStopsOnError() {
    val h = Harness()
    val steps =
        """[{"actionRef":"mesh.missing","onError":"abort"},{"actionRef":"mesh.up","onError":"abort"}]"""
    val r = h.call(env("macro.run", macroId = "m-abort", steps = steps))
    assertCat("not_found", r)
    assertEquals(0, h.backend.ups)
    assertEquals(1, r.result!!["steps"]!!.jsonArray.size)
    assertTrue(r.result!!["aborted"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun macroRunContinueKeepsGoing() {
    val h = Harness()
    val steps =
        """[{"actionRef":"mesh.missing","onError":"continue"},{"actionRef":"mesh.up","onError":"abort"}]"""
    val r = h.call(env("macro.run", macroId = "m-cont", steps = steps))
    assertCat("not_found", r)
    assertEquals(1, h.backend.ups)
    assertEquals(2, r.result!!["steps"]!!.jsonArray.size)
    assertFalse(r.result!!["aborted"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun macroRunDryRunValidatesOnly() {
    val h = Harness()
    val steps =
        """[{"actionRef":"mesh.up","onError":"abort"},{"actionRef":"mesh.down","onError":"abort"}]"""
    val r = h.call(env("macro.run", macroId = "m-dry", steps = steps, dryRun = true))
    assertTrue(r.ok)
    assertEquals(0, h.backend.ups)
    assertEquals(0, h.backend.downs)
    assertTrue(h.sink.pending.isEmpty())
    val step1 = r.result!!["steps"]!!.jsonArray[1].jsonObject
    assertTrue(step1["ok"]!!.jsonPrimitive.boolean)
    assertTrue(step1["result"]!!.jsonObject["confirmationRequired"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun macroRunDryRunRejectsUnknownAction() {
    val steps = """[{"actionRef":"nope","onError":"abort"}]"""
    val r = Harness().call(env("macro.run", macroId = "m-dry-bad", steps = steps, dryRun = true))
    assertCat("not_found", r)
  }

  @Test
  fun macroRunDestructiveStepIsGated() {
    val h = Harness()
    val steps =
        """[{"actionRef":"mesh.status","onError":"abort"},{"actionRef":"mesh.down","onError":"abort"}]"""
    val r = h.call(env("macro.run", macroId = "m-down", steps = steps))
    assertCat("confirmation_required", r)
    assertEquals(0, h.backend.downs)
    assertEquals(1, h.sink.pending.size)
    assertEquals(2, r.result!!["steps"]!!.jsonArray.size)
  }

  @Test
  fun macroRunEveryStepChecksCapability() {
    val caps =
        RemoteCapability.DEFAULTS +
            RemoteCapability("mesh.secret", FleetProtocol.KIND_ACTION, remoteAllowed = false, requiresConfirm = false)
    val steps =
        """[{"actionRef":"mesh.status","onError":"continue"},{"actionRef":"mesh.secret","onError":"abort"}]"""
    val r = Harness(capabilities = caps).call(env("macro.run", macroId = "m-cap", steps = steps))
    assertCat("capability_off", r)
  }

  @Test
  fun macroRunUnauthorizedIgnoresTrigger() {
    val steps = """[{"actionRef":"mesh.status","onError":"abort"}]"""
    val r =
        Harness()
            .call(
                env("macro.run", macroId = "m1", steps = steps, trigger = """{"authorized":true}"""),
                allowed = false,
            )
    assertCat("unauthorized", r)
  }

  @Test
  fun macroRunRequiresSteps() {
    assertCat("invalid_parameters", Harness().call(env("macro.run", macroId = "m1")))
  }

  @Test
  fun uuidNonceAccepted() {
    val r = Harness().call(env("capabilities.get", nonce = "01234567-89ab-cdef-0123-456789abcdef"))
    assertTrue(r.ok)
  }

  @Test
  fun requestIdReusedOnResponse() {
    val r = Harness().call(env("capabilities.get", requestId = "dca-reuse-9"))
    assertEquals("dca-reuse-9", r.requestId)
  }

  @Test
  fun expiredCheckedBeforeReplay() {
    val h = Harness()
    val nonce = nextNonce()
    assertCat("expired", h.call(env("capabilities.get", nonce = nonce, timestampMs = now0 - 200_000)))
    assertTrue(h.call(env("capabilities.get", nonce = nonce)).ok)
  }

  @Test
  fun emptyTriggerIsIgnoredOnHappyPath() {
    val r = Harness().call(env("remote.command", actionRef = "mesh.ip.get", trigger = "{}"))
    assertTrue(r.ok)
  }

  @Test
  fun nonceCacheKeyNormalizesUuid() {
    val cache = NonceCache()
    val h = Harness(nonceCache = cache)
    assertTrue(h.call(env("capabilities.get", nonce = "01234567-89AB-CDEF-0123-456789ABCDEF")).ok)
    assertCat("replay_detected", h.call(env("capabilities.get", nonce = "0123456789abcdef0123456789abcdef")))
  }

  @Test
  fun confirmUnknownPendingIsNotFound() {
    assertCat("not_found", Harness().dispatcher.confirmAndExecute("nope"))
  }

  @Test
  fun looksLikeRemoteEnvelope() {
    assertTrue(FleetProtocol.looksLikeRemoteEnvelope("""{"op":"capabilities.get"}"""))
    assertFalse(FleetProtocol.looksLikeRemoteEnvelope("""{"operation":"mesh.status"}"""))
    assertFalse(FleetProtocol.looksLikeRemoteEnvelope("{not json"))
    assertFalse(FleetProtocol.looksLikeRemoteEnvelope(null))
  }

}
