package com.tailscale.ipn.fleet

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FleetConfigBackupTest {
  private val node = "mesh-0123456789abcdef0123456789abcdef"
  private val src = "ai.droidcommand.app"
  private val now0 = 1_700_000_000_000L
  private val pkg = MeshBackup.PACKAGE_NAME

  private class FakeBackend : MeshBackend {
    override fun wireGuardModeActive() = false

    override fun status() = MeshStatus("Running", true, "https://hs.example.com", "100.64.0.1", null)

    override fun vpnConsentGranted() = true

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
      settings: MeshSettingsPayload = sampleLive(),
      externalControl: Boolean = false,
      val clock: java.util.concurrent.atomic.AtomicLong =
          java.util.concurrent.atomic.AtomicLong(now0),
  ) {
    val store = MemoryConfigStore(settings, externalControl)
    val sink = RecordingSink()
    val dispatcher =
        FleetDispatcher(
            node,
            FakeBackend(),
            clock = { clock.get() },
            confirmationSink = sink,
            configStore = store,
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

  private fun sampleLive() =
      MeshSettingsPayload(
          controlUrl = "https://live.example.com",
          tunnelMode = "MESH",
          activeWireGuardProfileId = "wg-live",
          prefs = MeshNonSecretPrefs(corpDns = true, wantRunning = true, exitNodeAllowLanAccess = false),
          wireGuardProfiles =
              listOf(
                  WireGuardProfileMeta("wg-live", "office"),
                  WireGuardProfileMeta("wg-keep", "home"),
              ),
      )

  private fun sampleImported() =
      MeshSettingsPayload(
          controlUrl = "https://imported.example.com",
          tunnelMode = "WIREGUARD",
          activeWireGuardProfileId = "wg-new",
          prefs = MeshNonSecretPrefs(corpDns = false, wantRunning = false, exitNodeAllowLanAccess = true),
          wireGuardProfiles =
              listOf(
                  WireGuardProfileMeta("wg-live", "office-imported"),
                  WireGuardProfileMeta("wg-new", "travel"),
              ),
      )

  private fun canonical(
      operation: String,
      nonce: String = nextNonce(),
      timestampMs: Long = now0,
      ttlMs: Long? = 120000,
      args: String? = null,
      extraParams: String = "",
      dryRun: Boolean? = null,
      targetNodeId: String = node,
  ): String {
    val fields = ArrayList<String>()
    fields.add("\"nonce\":\"$nonce\"")
    fields.add("\"timestampMs\":$timestampMs")
    if (ttlMs != null) fields.add("\"ttlMs\":$ttlMs")
    fields.add("\"requestId\":\"dca-1\"")
    fields.add("\"targetNodeRef\":\"$node\"")
    if (dryRun != null) fields.add("\"dryRun\":$dryRun")
    if (args != null) fields.add("\"args\":$args")
    if (extraParams.isNotEmpty()) fields.add(extraParams)
    return """{"protocol_major":1,"request_id":"dca-1","target_node_id":"$targetNodeId",
      |"operation":"$operation","timeout_ms":10000,"parameters":{${fields.joinToString(",")}}}"""
        .trimMargin()
        .replace("\n", "")
  }

  private fun env(op: String, actionRef: String = "", nonce: String = nextNonce()): String =
      """{"protocol_major":1,"protocol_minor":1,"op":"$op","requestId":"dca-1",
        |"targetNodeRef":"$node","actionRef":"$actionRef","params":{},
        |"nonce":"$nonce","timestampMs":$now0,"ttlMs":120000,"destructive":false}"""
          .trimMargin()
          .replace("\n", "")

  private fun bundleObject(payload: MeshSettingsPayload): String =
      UnifiedBackupCodec.encodeBundle(
              UnifiedFleetBackup(
                  version = 1,
                  createdAtMs = now0,
                  sourceNodeRef = "controller",
                  wrapping = BackupWrapping.NONE,
                  entries = listOf(UnifiedBackupCodec.thisNodeEntry(payload, pkg)),
              )
          )
          .toString()

  private fun importArgs(
      payload: MeshSettingsPayload = sampleImported(),
      dryRun: Boolean? = null,
      policy: String? = null,
      extra: String = "",
  ): String {
    val parts = ArrayList<String>()
    parts.add("\"bundle\":${bundleObject(payload)}")
    if (dryRun != null) parts.add("\"dryRun\":$dryRun")
    if (policy != null) parts.add("\"conflictPolicy\":\"$policy\"")
    if (extra.isNotEmpty()) parts.add(extra)
    return "{${parts.joinToString(",")}}"
  }

  private fun assertCat(cat: String, r: BridgeResponse) {
    assertFalse(r.ok)
    assertEquals(cat, r.error?.category)
  }

  private fun changeMap(result: JsonObject): Map<String, String> =
      result["changes"]!!.jsonArray.associate {
        val o = it.jsonObject
        o["path"]!!.jsonPrimitive.content to o["action"]!!.jsonPrimitive.content
      }

  @Test
  fun exportReturnsExactlyOneMeshEntry() {
    val r = Harness().call(canonical(FleetProtocol.OP_CONFIG_EXPORT))
    assertTrue(r.ok)
    val entry = r.result!!
    assertEquals(pkg, entry["packageName"]!!.jsonPrimitive.content)
    assertEquals(1, entry["schemaVersion"]!!.jsonPrimitive.content.toInt())
    assertNull(entry["ciphertext"])
    val clear = entry["clearPayload"]!!.jsonPrimitive.content
    val payload = UnifiedBackupCodec.decodePayload(clear)
    assertEquals("https://live.example.com", payload.controlUrl)
    assertEquals("MESH", payload.tunnelMode)
    assertEquals("wg-live", payload.activeWireGuardProfileId)
    assertEquals(true, payload.prefs?.corpDns)
    assertEquals(listOf("wg-live", "wg-keep"), payload.wireGuardProfiles.map { it.id })
    assertEquals("office", payload.wireGuardProfiles[0].name)
    val text = entry.toString().lowercase()
    assertFalse(text.contains("privatekey"))
    assertFalse(text.contains("preshared"))
    assertFalse(text.contains("authkey"))
  }

  @Test
  fun exportRefusesSecretNames() {
    val dirty =
        sampleLive().copy(wireGuardProfiles = listOf(WireGuardProfileMeta("wg-1", "my privateKey")))
    assertCat(
        "invalid_parameters",
        Harness(dirty).call(canonical(FleetProtocol.OP_CONFIG_EXPORT)),
    )
  }

  @Test
  fun exportRefusesMeshAddresses() {
    val dirty = sampleLive().copy(controlUrl = "https://100.64.0.1")
    assertCat(
        "invalid_parameters",
        Harness(dirty).call(canonical(FleetProtocol.OP_CONFIG_EXPORT)),
    )
  }

  @Test
  fun importRefusesMeshAddresses() {
    val raw =
        """{"version":1,"createdAtMs":$now0,"sourceNodeRef":"controller","wrapping":"none",
          |"entries":[{"packageName":"$pkg","schemaVersion":1,
          |"clearPayload":"{\"controlUrl\":\"https://100.64.0.1\"}"}]}"""
            .trimMargin()
            .replace("\n", "")
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = """{"bundle":$raw,"dryRun":true}""",
                )
            )
    assertCat("invalid_parameters", r)
  }

  @Test
  fun importRefusesForbiddenIpKeys() {
    val raw =
        """{"version":1,"createdAtMs":$now0,"sourceNodeRef":"controller","wrapping":"none",
          |"entries":[{"packageName":"$pkg","schemaVersion":1,
          |"clearPayload":"{\"controlUrl\":\"https://hs.example\",\"ipv4\":\"none\",\"ips\":[]}"}]}"""
            .trimMargin()
            .replace("\n", "")
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = """{"bundle":$raw,"dryRun":true}""",
                )
            )
    assertCat("invalid_parameters", r)
  }

  @Test
  fun importRefusesSecretNames() {
    val raw =
        """{"version":1,"createdAtMs":$now0,"sourceNodeRef":"controller","wrapping":"none",
          |"entries":[{"packageName":"$pkg","schemaVersion":1,
          |"clearPayload":"{\"controlUrl\":\"https://x\",\"password\":\"nopenope\"}"}]}"""
            .trimMargin()
            .replace("\n", "")
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = """{"bundle":$raw,"dryRun":true}""",
                )
            )
    assertCat("invalid_parameters", r)
  }

  @Test
  fun importDryRunSkipKeepsLive() {
    val h = Harness()
    val r =
        h.call(
            canonical(
                FleetProtocol.OP_CONFIG_IMPORT,
                args = importArgs(dryRun = true, policy = "skip"),
            )
        )
    assertTrue(r.ok)
    assertTrue(r.result!!["dryRun"]!!.jsonPrimitive.boolean)
    assertFalse(r.result!!["wouldWrite"]!!.jsonPrimitive.boolean)
    assertFalse(r.result!!["externalControlEnabled"]!!.jsonPrimitive.boolean)
    assertEquals("skip", r.result!!["conflictPolicy"]!!.jsonPrimitive.content)
    val actions = changeMap(r.result!!)
    assertEquals("skip", actions["controlUrl"])
    assertEquals("keep", actions["wireGuardProfiles[wg-live]"])
    assertEquals("skip", actions["wireGuardProfiles[wg-new]"])
    assertEquals("https://live.example.com", h.store.snapshot().controlUrl)
    assertEquals(2, h.store.snapshot().wireGuardProfiles.size)
    assertTrue(h.sink.pending.isEmpty())
  }

  @Test
  fun importDryRunOverwriteTakesImported() {
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = importArgs(dryRun = true, policy = "overwrite"),
                )
            )
    assertTrue(r.ok)
    val actions = changeMap(r.result!!)
    assertEquals("overwrite", actions["controlUrl"])
    assertEquals("overwrite", actions["tunnelMode"])
    assertEquals("overwrite", actions["wireGuardProfiles[wg-live]"])
    assertEquals("add", actions["wireGuardProfiles[wg-new]"])
  }

  @Test
  fun importDryRunMergeByIdFillsGaps() {
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = importArgs(dryRun = true, policy = "merge-by-id"),
                )
            )
    assertTrue(r.ok)
    val actions = changeMap(r.result!!)
    assertEquals("keep", actions["controlUrl"])
    assertEquals("keep", actions["wireGuardProfiles[wg-live]"])
    assertEquals("add", actions["wireGuardProfiles[wg-new]"])
  }

  @Test
  fun importDefaultPolicyIsSkip() {
    val r =
        Harness()
            .call(canonical(FleetProtocol.OP_CONFIG_IMPORT, args = importArgs(dryRun = true)))
    assertTrue(r.ok)
    assertEquals("skip", r.result!!["conflictPolicy"]!!.jsonPrimitive.content)
  }

  @Test
  fun importConfirmGateAndApply() {
    val h = Harness(externalControl = true)
    val r =
        h.call(
            canonical(
                FleetProtocol.OP_CONFIG_IMPORT,
                args = importArgs(dryRun = false, policy = "overwrite"),
            )
        )
    assertCat("confirmation_required", r)
    assertEquals(1, h.sink.pending.size)
    assertEquals(FleetProtocol.OP_CONFIG_IMPORT, h.sink.pending[0].actionRef)
    assertEquals("https://live.example.com", h.store.snapshot().controlUrl)
    assertTrue(h.store.isExternalControlEnabled())

    val applied = h.dispatcher.confirmAndExecute(h.sink.pending[0].id)
    assertTrue(applied.ok)
    assertTrue(applied.result!!["applied"]!!.jsonPrimitive.boolean)
    assertFalse(applied.result!!["externalControlEnabled"]!!.jsonPrimitive.boolean)
    assertEquals("https://imported.example.com", h.store.snapshot().controlUrl)
    assertEquals("WIREGUARD", h.store.snapshot().tunnelMode)
    assertEquals(
        listOf("wg-live", "wg-keep", "wg-new"),
        h.store.snapshot().wireGuardProfiles.map { it.id },
    )
    assertEquals("office-imported", h.store.snapshot().wireGuardProfiles[0].name)
    assertFalse(h.store.isExternalControlEnabled())
  }

  @Test
  fun importDenyDoesNotWrite() {
    val h = Harness()
    assertCat(
        "confirmation_required",
        h.call(
            canonical(
                FleetProtocol.OP_CONFIG_IMPORT,
                args = importArgs(policy = "overwrite"),
            )
        ),
    )
    h.dispatcher.denyConfirmation(h.sink.pending[0].id)
    assertCat("not_found", h.dispatcher.confirmAndExecute(h.sink.pending[0].id))
    assertEquals("https://live.example.com", h.store.snapshot().controlUrl)
  }

  @Test
  fun importNeverEnablesExternalControl() {
    val h = Harness(externalControl = true)
    val entry =
        UnifiedBackupEntry(
            pkg,
            MeshBackup.SCHEMA_VERSION,
            clearPayload =
                """{"controlUrl":"https://imported.example.com","externalControlEnabled":true}""",
        )
    val bundle =
        UnifiedBackupCodec.encodeBundle(
            UnifiedFleetBackup(1, now0, "controller", BackupWrapping.NONE, listOf(entry))
        )
    assertCat(
        "confirmation_required",
        h.call(
            canonical(
                FleetProtocol.OP_CONFIG_IMPORT,
                args = """{"bundle":$bundle,"conflictPolicy":"overwrite"}""",
            )
        ),
    )
    assertTrue(h.store.isExternalControlEnabled())
    val applied = h.dispatcher.confirmAndExecute(h.sink.pending[0].id)
    assertTrue(applied.ok)
    assertFalse(applied.result!!["externalControlEnabled"]!!.jsonPrimitive.boolean)
    assertFalse(h.store.isExternalControlEnabled())
  }

  @Test
  fun capabilitiesAdvertiseConfigOps() {
    val r = Harness().call(env("capabilities.get"))
    assertTrue(r.ok)
    val byName =
        r.result!!["operations"]!!.jsonArray.associate {
          it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject
        }
    val exp = byName.getValue("config.export")
    assertEquals("action", exp["kind"]!!.jsonPrimitive.content)
    assertFalse(exp["remoteAllowed"]!!.jsonPrimitive.boolean)
    assertFalse(exp["requiresConfirm"]!!.jsonPrimitive.boolean)
    val imp = byName.getValue("config.import")
    assertEquals("action", imp["kind"]!!.jsonPrimitive.content)
    assertFalse(imp["remoteAllowed"]!!.jsonPrimitive.boolean)
    assertTrue(imp["requiresConfirm"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun remoteCommandIsCapabilityOff() {
    assertCat("capability_off", Harness().call(env("remote.command", actionRef = "config.export")))
    assertCat("capability_off", Harness().call(env("remote.command", actionRef = "config.import")))
  }

  @Test
  fun unauthorizedCallerRejected() {
    val r = Harness().call(canonical(FleetProtocol.OP_CONFIG_EXPORT), allowed = false)
    assertCat("unauthorized_caller", r)
    assertNull(r.nodeId)
  }

  @Test
  fun replayAndExpiry() {
    val h = Harness()
    val nonce = nextNonce()
    assertTrue(h.call(canonical(FleetProtocol.OP_CONFIG_EXPORT, nonce = nonce)).ok)
    assertCat("replay_detected", h.call(canonical(FleetProtocol.OP_CONFIG_EXPORT, nonce = nonce)))
    assertCat(
        "expired",
        h.call(canonical(FleetProtocol.OP_CONFIG_EXPORT, timestampMs = now0 - 200_000)),
    )
  }

  @Test
  fun unknownPackageIsNotFound() {
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_EXPORT,
                    args = """{"packageName":"ai.droidcommand.app"}""",
                )
            )
    assertCat("not_found", r)
  }

  @Test
  fun wrappingOtherThanNoneIsInvalid() {
    val bundle =
        """{"version":1,"createdAtMs":$now0,"sourceNodeRef":"controller",
          |"wrapping":"raw-aes-256-gcm","entries":[{"packageName":"$pkg","schemaVersion":1,
          |"ciphertext":"v1.aaaa.bbbb"}]}"""
            .trimMargin()
            .replace("\n", "")
    assertCat(
        "invalid_parameters",
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_IMPORT,
                    args = """{"bundle":$bundle,"dryRun":true}""",
                )
            ),
    )
  }

  @Test
  fun unknownParamKeysAreIgnored() {
    val r =
        Harness()
            .call(
                canonical(
                    FleetProtocol.OP_CONFIG_EXPORT,
                    extraParams = """"futureField":true,"trigger":{"autoApprove":true}""",
                )
            )
    assertTrue(r.ok)
  }

  @Test
  fun secretScannerRefusesVariants() {
    val bad =
        listOf(
            """{"apiKey":"x"}""",
            """{"api_token":"x"}""",
            """{"private_key":"x"}""",
            """{"presharedKey":"x"}""",
            """{"authKey":"x"}""",
            """{"nodeKey":"x"}""",
            """{"twilio":"x"}""",
            """{"numberPool":[]}""",
            """{"codeWord":"x"}""",
            """{"cookie":"x"}""",
        )
    for (json in bad) {
      try {
        BackupSecretScanner.refuseIfSecret(MeshBackup.json.parseToJsonElement(json))
        fail("expected refuse for $json")
      } catch (e: InvalidParametersException) {
        assertTrue(e.message!!.contains("forbidden"))
      }
    }
    BackupSecretScanner.refuseIfSecret(
        MeshBackup.json.parseToJsonElement("""{"controlUrl":"https://hs.example.com","tunnelMode":"MESH"}""")
    )
  }
}
