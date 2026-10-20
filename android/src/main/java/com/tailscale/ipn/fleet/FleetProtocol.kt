// Victorious Mesh
// Fleet IPC bridge: pure-Kotlin protocol core (no Android deps, JVM unit-testable).
// Spec: EXTERNAL_NODE mesh.* ops (2026-10-18) + FROZEN remote-control envelope v1
// (BRIDGE_PROTOCOL_MAJOR=1, MINOR=1) + STATUS-TRAY node.status.get (droidcommand-AI #235)
// + UNIFIED_BACKUP config.export / config.import (droidcommand-AI #236).

package com.tailscale.ipn.fleet

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object FleetProtocol {
  const val PROTOCOL_MAJOR = 1
  const val PROTOCOL_MINOR = 1
  // Frozen intent action. Not derived from applicationId.
  const val ACTION_BRIDGE = "com.victorious.mesh.action.BRIDGE"
  const val MSG_REQUEST = 1
  const val MSG_RESPONSE = 2
  const val KEY_REQUEST = "request"
  const val KEY_RESPONSE = "response"

  /** Node id prefix. PENDING RATIFICATION by the fleet spec; change only here. */
  const val NODE_ID_PREFIX = "mesh-"
  val NODE_ID_REGEX = Regex("^" + Regex.escape(NODE_ID_PREFIX) + "[0-9a-f]{32}$")

  const val OP_CAPABILITIES = "mesh.capabilities.get"
  const val OP_STATUS = "mesh.status"
  const val OP_UP = "mesh.up"
  const val OP_DOWN = "mesh.down"
  const val OP_IP_GET = "mesh.ip.get"
  const val OP_NODE_STATUS = "node.status.get"
  const val OP_CONFIG_EXPORT = "config.export"
  const val OP_CONFIG_IMPORT = "config.import"
  val OPERATIONS =
      listOf(
          OP_CAPABILITIES,
          OP_STATUS,
          OP_UP,
          OP_DOWN,
          OP_IP_GET,
          OP_NODE_STATUS,
          OP_CONFIG_EXPORT,
          OP_CONFIG_IMPORT,
      )

  const val OP_REMOTE_CAPABILITIES = "capabilities.get"
  const val OP_REMOTE_COMMAND = "remote.command"
  const val OP_MACRO_STEP = "macro.step"
  const val OP_MACRO_RUN = "macro.run"

  const val KIND_READ = "read"
  const val KIND_ACTION = "action"
  const val KIND_DESTRUCTIVE = "destructive"

  const val ON_ERROR_ABORT = "abort"
  const val ON_ERROR_CONTINUE = "continue"

  const val DEFAULT_TTL_MS = 120_000L
  const val MAX_TTL_MS = 300_000L
  /** Future-only skew: reject when timestampMs > now + this (past timestamps use TTL only). */
  const val MAX_FUTURE_SKEW_MS = 120_000L
  const val NONCE_RETAIN_MS = 10L * 60L * 1000L
  const val NONCE_CACHE_MAX_ENTRIES = 4096

  private val NONCE_HEX = Regex("^[0-9a-fA-F]{32}$")
  private val NONCE_UUID =
      Regex(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
      )

  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun newNodeId(random: SecureRandom = SecureRandom()): String {
    val b = ByteArray(16).also { random.nextBytes(it) }
    return NODE_ID_PREFIX + b.joinToString("") { "%02x".format(it) }
  }

  fun isValidNodeId(id: String) = NODE_ID_REGEX.matches(id)

  fun isValidNonce(nonce: String) = NONCE_HEX.matches(nonce) || NONCE_UUID.matches(nonce)

  fun normalizeNonce(nonce: String) = nonce.replace("-", "").lowercase()

  /** ttlMs above MAX_TTL_MS is clamped; callers must already reject non-positive values. */
  fun clampTtl(ttlMs: Long): Long = ttlMs.coerceAtMost(MAX_TTL_MS)

  fun looksLikeRemoteEnvelope(raw: String?): Boolean {
    return try {
      val el = json.parseToJsonElement(raw ?: "")
      el is JsonObject && "op" in el
    } catch (_: Exception) {
      false
    }
  }
}

object ErrorCategory {
  const val MALFORMED_REQUEST = "malformed_request"
  const val PROTOCOL_MISMATCH = "protocol_mismatch"
  const val NODE_MISMATCH = "node_mismatch"
  const val UNSUPPORTED_OPERATION = "unsupported_operation"
  const val INVALID_PARAMETERS = "invalid_parameters"
  const val SAFETY_REFUSED = "safety_refused"
  const val VERIFY_FAILED = "verify_failed"
  const val INTERNAL = "internal"
  const val EXTERNAL_CONTROL_DISABLED = "external_control_disabled"
  const val UNAUTHORIZED_CALLER = "unauthorized_caller"
  const val NOT_PAIRED = "not_paired"
  const val NOT_FOUND = "not_found"
  const val UNAUTHORIZED = "unauthorized"
  const val REPLAY_DETECTED = "replay_detected"
  const val EXPIRED = "expired"
  const val CONFIRMATION_REQUIRED = "confirmation_required"
  const val UNSUPPORTED_OP = "unsupported_op"
  const val CAPABILITY_OFF = "capability_off"
}

@Serializable
data class BridgeRequest(
    @SerialName("protocol_major") val protocolMajor: Int = 1,
    @SerialName("request_id") val requestId: String,
    @SerialName("target_node_id") val targetNodeId: String,
    val operation: String,
    @SerialName("timeout_ms") val timeoutMs: Int = 10000,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RemoteEnvelope(
    @SerialName("protocol_major") val protocolMajor: Int = FleetProtocol.PROTOCOL_MAJOR,
    @SerialName("protocol_minor") val protocolMinor: Int = FleetProtocol.PROTOCOL_MINOR,
    val op: String,
    val requestId: String,
    val targetNodeRef: String,
    val actionRef: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long? = null,
    val destructive: Boolean = false,
    val trigger: JsonObject = JsonObject(emptyMap()),
    val macroId: String? = null,
    val stepIndex: Int? = null,
    val onError: String? = null,
    val steps: List<MacroStep>? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class MacroStep(
    val actionRef: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val onError: String = FleetProtocol.ON_ERROR_ABORT,
    val destructive: Boolean = false,
)

@Serializable data class BridgeError(val category: String, val message: String)

@Serializable data class BridgeProgress(val phase: String, val done: Int, val total: Int)

@Serializable
data class BridgeResponse(
    @SerialName("protocol_major") val protocolMajor: Int = FleetProtocol.PROTOCOL_MAJOR,
    @SerialName("protocol_minor") val protocolMinor: Int = FleetProtocol.PROTOCOL_MINOR,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: BridgeError? = null,
    // Optional streaming frame marker (unused for now: all our responses are final).
    val progress: BridgeProgress? = null,
)

/** Mesh state snapshot. Contains NO keys/credentials by construction. */
data class MeshStatus(
    val backendState: String,
    val connected: Boolean,
    val controlUrl: String?,
    val ipv4: String?,
    val ipv6: String?,
)

/** Backend abstraction over the app's IPN state + start/stop paths. */
interface MeshBackend {
  fun status(): MeshStatus

  fun vpnConsentGranted(): Boolean

  fun up()

  fun down()

  /**
   * True while the app's single VpnService is running a plain WireGuard tunnel instead of the
   * mesh. The mesh is down in that case.
   */
  fun wireGuardModeActive(): Boolean = false
}

/** Mesh status as seen by the fleet: in WireGuard mode the mesh is reported down. */
fun MeshStatus.forTunnelMode(wireGuardActive: Boolean): MeshStatus =
    if (wireGuardActive) copy(backendState = "Stopped", connected = false, ipv4 = null, ipv6 = null)
    else this

class InvalidParametersException(msg: String) : Exception(msg)

class ActionNotFound(msg: String) : Exception(msg)

data class RemoteCapability(
    val name: String,
    val kind: String,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
) {
  companion object {
    val DEFAULTS: List<RemoteCapability> =
        listOf(
            RemoteCapability(FleetProtocol.OP_CAPABILITIES, FleetProtocol.KIND_READ, true, false),
            RemoteCapability(FleetProtocol.OP_STATUS, FleetProtocol.KIND_READ, true, false),
            RemoteCapability(FleetProtocol.OP_IP_GET, FleetProtocol.KIND_READ, true, false),
            RemoteCapability(FleetProtocol.OP_NODE_STATUS, FleetProtocol.KIND_READ, true, false),
            RemoteCapability(FleetProtocol.OP_UP, FleetProtocol.KIND_ACTION, true, false),
            RemoteCapability(FleetProtocol.OP_DOWN, FleetProtocol.KIND_DESTRUCTIVE, true, true),
            RemoteCapability(FleetProtocol.OP_CONFIG_EXPORT, FleetProtocol.KIND_ACTION, false, false),
            RemoteCapability(FleetProtocol.OP_CONFIG_IMPORT, FleetProtocol.KIND_ACTION, false, true),
        )
  }
}

enum class RemoteOp {
  CAPABILITIES_GET,
  REMOTE_COMMAND,
  MACRO_STEP,
  MACRO_RUN,
}

fun parseRemoteOp(raw: String): RemoteOp? =
    when (raw) {
      FleetProtocol.OP_REMOTE_CAPABILITIES -> RemoteOp.CAPABILITIES_GET
      FleetProtocol.OP_REMOTE_COMMAND -> RemoteOp.REMOTE_COMMAND
      FleetProtocol.OP_MACRO_STEP -> RemoteOp.MACRO_STEP
      FleetProtocol.OP_MACRO_RUN -> RemoteOp.MACRO_RUN
      else -> null
    }

data class PendingConfirmation(
    val id: String,
    val actionRef: String,
    val requestId: String,
    val sourceNodeRef: String,
    val expiresAtMs: Long,
    val importPayloadJson: String? = null,
    val importResult: JsonObject? = null,
)

fun interface ConfirmationSink {
  fun onConfirmationRequired(pending: PendingConfirmation)
}

class ConfirmationRegistry(private val random: SecureRandom = SecureRandom()) {
  private val pending = LinkedHashMap<String, PendingConfirmation>()

  @Synchronized
  fun create(
      actionRef: String,
      requestId: String,
      sourceNodeRef: String,
      expiresAtMs: Long,
      importPayloadJson: String? = null,
      importResult: JsonObject? = null,
  ): PendingConfirmation {
    val id = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val p =
        PendingConfirmation(
            id,
            actionRef,
            requestId,
            sourceNodeRef,
            expiresAtMs,
            importPayloadJson,
            importResult,
        )
    pending[id] = p
    return p
  }

  @Synchronized
  fun peek(id: String): PendingConfirmation? = pending[id]

  @Synchronized
  fun take(id: String, nowMs: Long): PendingConfirmation? {
    val p = pending.remove(id) ?: return null
    if (nowMs > p.expiresAtMs) return null
    return p
  }

  @Synchronized
  fun takeExpiredOrMissing(id: String, nowMs: Long): Pair<PendingConfirmation?, Boolean> {
    val p = pending[id] ?: return null to false
    if (nowMs > p.expiresAtMs) {
      pending.remove(id)
      return null to true
    }
    pending.remove(id)
    return p to false
  }

  @Synchronized fun deny(id: String) { pending.remove(id) }

  @Synchronized fun size(): Int = pending.size
}

/**
 * Replay cache keyed by (sourceNodeRef, nonce). Entries are kept at least [minRetainMs] and the
 * map is bounded by [maxEntries] (oldest inserted keys evicted first after expiry sweep).
 */
class NonceCache(
    private val minRetainMs: Long = FleetProtocol.NONCE_RETAIN_MS,
    private val maxEntries: Int = FleetProtocol.NONCE_CACHE_MAX_ENTRIES,
) {
  private val map = LinkedHashMap<String, Long>()

  @Synchronized
  fun seen(sourceNodeRef: String, nonce: String, nowMs: Long): Boolean {
    evict(nowMs)
    val key = cacheKey(sourceNodeRef, nonce)
    if (key in map) return true
    map[key] = nowMs + minRetainMs
    evict(nowMs)
    return false
  }

  @Synchronized fun size(): Int = map.size

  @Synchronized
  fun contains(sourceNodeRef: String, nonce: String): Boolean = cacheKey(sourceNodeRef, nonce) in map

  private fun evict(nowMs: Long) {
    val it = map.entries.iterator()
    while (it.hasNext()) {
      if (it.next().value <= nowMs) it.remove()
    }
    while (map.size > maxEntries) {
      val oldest = map.entries.iterator()
      if (!oldest.hasNext()) break
      oldest.next()
      oldest.remove()
    }
  }

  companion object {
    fun cacheKey(sourceNodeRef: String, nonce: String) =
        sourceNodeRef + "\u0000" + FleetProtocol.normalizeNonce(nonce)
  }
}

/** Signing-cert allowlist for controller apps. */
object CallerAllowlist {
  private const val DCA_DEBUG_CERT =
      "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"

  // droidcommand-AI release cert = shared fleet release key (confirmed 2026-10-18).
  // Mesh's own release key is separate and is NOT this cert.
  private const val DCA_RELEASE_CERT =
      "9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D"

  private val DCA_CERTS = setOf(normalize(DCA_DEBUG_CERT), normalize(DCA_RELEASE_CERT))

  val ENTRIES: Map<String, Set<String>> =
      mapOf(
          "ai.droidcommand.app" to DCA_CERTS,
          "ai.droidcommand.app.debug" to DCA_CERTS,
      )

  fun normalize(fp: String) = fp.replace(":", "").replace(" ", "").lowercase()

  fun sha256Hex(certBytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") {
        "%02x".format(it)
      }

  /** True if any (package, cert sha256) pair is allowlisted. */
  fun isAllowed(
      candidates: Map<String, List<String>>,
      entries: Map<String, Set<String>> = ENTRIES,
  ): Boolean =
      candidates.any { (pkg, certs) ->
        val allowed = entries[pkg] ?: return@any false
        certs.any { normalize(it) in allowed }
      }
}

/**
 * Dispatches a raw request string.
 *
 * Authorization is ONLY the caller package + cert allowlist (see [callerAllowed] /
 * [sourceNodeRef]). Envelope fields including trigger.* never grant access.
 *
 * Legacy mesh.* BridgeRequest check order (backward compatible):
 * 1. caller allowlist -> unauthorized_caller
 * 2. JSON parse -> malformed_request
 * 3. protocol_major -> protocol_mismatch
 * 4. target_node_id -> node_mismatch
 * 5. node.status.get / config.export / config.import are standalone BridgeRequest
 *    ops (not remote.command actionRefs): envelope fields live in parameters;
 *    same replay/expiry. config.import (non-dryRun) needs on-phone confirm.
 *    External control matches other fleet ops (mesh.status).
 * 6. external control (except mesh.capabilities.get) -> external_control_disabled
 * 7. operation -> unsupported_operation / invalid_parameters / safety_refused / internal
 *
 * Remote envelope v1.1 check order:
 * 1. caller allowlist -> unauthorized
 * 2. JSON parse / required fields -> invalid_parameters
 * 3. targetNodeRef: blank/malformed -> invalid_parameters; unknown -> not_found;
 *    known but not paired with sender -> not_paired
 * 4. ttl / nonce format -> invalid_parameters (ttl above max is clamped)
 * 5. now > timestampMs+ttlMs, or timestampMs > now+120s future skew -> expired
 * 6. duplicate (sourceNodeRef, nonce) -> replay_detected
 * 7. external control (except capabilities.get) -> external_control_disabled
 * 8. op / action / capability / confirmation / mesh action
 */
/**
 * Which node refs this phone knows, and which callers are paired with them.
 * Default: this install's node id is the only known node; any allowlisted sender
 * is treated as paired with it. Pairing is never taken from envelope fields.
 */
fun interface NodePairing {
  fun isPaired(sourceNodeRef: String, targetNodeRef: String): Boolean
}

class FleetDispatcher(
    private val nodeId: String,
    private val backend: MeshBackend,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nonceCache: NonceCache = NonceCache(),
    private val confirmations: ConfirmationRegistry = ConfirmationRegistry(),
    private val confirmationSink: ConfirmationSink = ConfirmationSink {},
    private val capabilities: List<RemoteCapability> = RemoteCapability.DEFAULTS,
    private val knownNodeRefs: Set<String>? = null,
    private val pairing: NodePairing = NodePairing { _, target -> target == nodeId },
    private val packageName: String = NodeStatusSnapshot.PACKAGE_VICTORIOUS_MESH,
    private val configStore: ConfigStore = MemoryConfigStore(),
) {

  fun handle(
      raw: String?,
      callerAllowed: Boolean,
      controlEnabled: Boolean,
      sourceNodeRef: String = "",
  ): BridgeResponse {
    val src = sourceNodeRef.ifBlank { "unknown-caller" }
    if (!callerAllowed) {
      val cat =
          if (FleetProtocol.looksLikeRemoteEnvelope(raw)) ErrorCategory.UNAUTHORIZED
          else ErrorCategory.UNAUTHORIZED_CALLER
      return err(null, cat, "caller is not an allowlisted controller", withNode = false)
    }
    if (FleetProtocol.looksLikeRemoteEnvelope(raw)) {
      return handleEnvelope(raw, controlEnabled, src)
    }
    return handleLegacy(raw, controlEnabled, src)
  }

  fun confirmAndExecute(pendingId: String): BridgeResponse {
    val now = clock()
    val (pending, expired) = confirmations.takeExpiredOrMissing(pendingId, now)
    if (expired) return err(null, ErrorCategory.EXPIRED, "pending confirmation expired")
    if (pending == null) return err(null, ErrorCategory.NOT_FOUND, "no pending confirmation")
    return try {
      if (pending.actionRef == FleetProtocol.OP_CONFIG_IMPORT) {
        ok(pending.requestId, applyPendingImport(pending))
      } else {
        ok(pending.requestId, runMeshAction(pending.actionRef))
      }
    } catch (e: InvalidParametersException) {
      err(pending.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: SafetyRefused) {
      err(pending.requestId, ErrorCategory.SAFETY_REFUSED, e.message ?: "refused")
    } catch (e: Exception) {
      err(pending.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  fun denyConfirmation(pendingId: String) = confirmations.deny(pendingId)

  private fun handleLegacy(
      raw: String?,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse {
    val req =
        try {
          FleetProtocol.json.decodeFromString(BridgeRequest.serializer(), raw ?: "")
        } catch (_: Exception) {
          return err(null, ErrorCategory.MALFORMED_REQUEST, "request is not a valid BridgeRequest")
        }
    if (req.protocolMajor != FleetProtocol.PROTOCOL_MAJOR)
        return err(
            req.requestId,
            ErrorCategory.PROTOCOL_MISMATCH,
            "unsupported protocol_major ${req.protocolMajor}; expected ${FleetProtocol.PROTOCOL_MAJOR}",
        )
    if (req.targetNodeId != nodeId)
        return err(req.requestId, ErrorCategory.NODE_MISMATCH, "target_node_id does not match this node")
    if (req.operation == FleetProtocol.OP_NODE_STATUS)
        return handleNodeStatus(req, controlEnabled, sourceNodeRef)
    if (req.operation == FleetProtocol.OP_CONFIG_EXPORT)
        return handleConfigExport(req, controlEnabled, sourceNodeRef)
    if (req.operation == FleetProtocol.OP_CONFIG_IMPORT)
        return handleConfigImport(req, controlEnabled, sourceNodeRef)
    if (!controlEnabled && req.operation != FleetProtocol.OP_CAPABILITIES)
        return err(
            req.requestId,
            ErrorCategory.EXTERNAL_CONTROL_DISABLED,
            "external control is turned off in Victorious Mesh settings",
        )
    return try {
      if (req.operation !in FleetProtocol.OPERATIONS)
          return err(req.requestId, ErrorCategory.UNSUPPORTED_OPERATION, "unsupported operation")
      if (req.parameters.isNotEmpty() && req.operation != FleetProtocol.OP_NODE_STATUS)
          throw InvalidParametersException("operation ${req.operation} takes no parameters")
      if (req.timeoutMs <= 0) throw InvalidParametersException("timeout_ms must be positive")
      ok(req.requestId, runMeshAction(req.operation, req.parameters))
    } catch (e: InvalidParametersException) {
      err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      err(req.requestId, ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: SafetyRefused) {
      err(req.requestId, ErrorCategory.SAFETY_REFUSED, e.message ?: "refused")
    } catch (e: Exception) {
      err(req.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  /**
   * Canonical `node.status.get`: BridgeRequest with v1.1 envelope fields in
   * `parameters`. Same package+cert auth (already checked), replay, and expiry
   * as remote control. Does not enter `remote.command` confirmation gating.
   * External control matches other read ops (`mesh.status`).
   */
  private fun handleNodeStatus(
      req: BridgeRequest,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse {
    consumeStandaloneEnvelope(req, controlEnabled, sourceNodeRef)?.let { return it }
    return try {
      if (req.timeoutMs <= 0) throw InvalidParametersException("timeout_ms must be positive")
      ok(req.requestId, nodeStatusResult(req.parameters))
    } catch (e: InvalidParametersException) {
      err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      err(req.requestId, ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: Exception) {
      err(req.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  /**
   * Canonical `config.export`: one UnifiedBackupEntry for this app. Never
   * secrets. Not an actionRef under remote.command.
   */
  private fun handleConfigExport(
      req: BridgeRequest,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse {
    consumeStandaloneEnvelope(req, controlEnabled, sourceNodeRef)?.let { return it }
    return try {
      if (req.timeoutMs <= 0) throw InvalidParametersException("timeout_ms must be positive")
      ok(req.requestId, exportConfig(req.parameters))
    } catch (e: InvalidParametersException) {
      err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      err(req.requestId, ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: Exception) {
      err(req.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  /**
   * Canonical `config.import`: dry-run returns the planned diff; a real import
   * always needs on-phone Approve/Deny and never enables external control.
   */
  private fun handleConfigImport(
      req: BridgeRequest,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse {
    consumeStandaloneEnvelope(req, controlEnabled, sourceNodeRef)?.let { return it }
    return try {
      if (req.timeoutMs <= 0) throw InvalidParametersException("timeout_ms must be positive")
      importConfig(req, sourceNodeRef)
    } catch (e: InvalidParametersException) {
      err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      err(req.requestId, ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: Exception) {
      err(req.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  /**
   * Shared v1.1 envelope checks for standalone BridgeRequest ops. Null means
   * the caller may proceed (nonce has been recorded).
   */
  private fun consumeStandaloneEnvelope(
      req: BridgeRequest,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse? {
    if (!controlEnabled)
        return err(
            req.requestId,
            ErrorCategory.EXTERNAL_CONTROL_DISABLED,
            "external control is turned off in Victorious Mesh settings",
        )
    val params = req.parameters
    val targetRef = params["targetNodeRef"]?.jsonPrimitive?.contentOrNull?.trim()
    if (targetRef != null) {
      if (targetRef.isBlank() || !FleetProtocol.isValidNodeId(targetRef))
          return err(req.requestId, ErrorCategory.INVALID_PARAMETERS, "targetNodeRef is blank or malformed")
      val known = knownNodeRefs ?: setOf(nodeId)
      if (targetRef !in known)
          return err(req.requestId, ErrorCategory.NOT_FOUND, "targetNodeRef is unknown or unresolvable")
      if (!pairing.isPaired(sourceNodeRef, targetRef))
          return err(req.requestId, ErrorCategory.NOT_PAIRED, "targetNodeRef is not paired with this caller")
    }
    val nonce = params["nonce"]?.jsonPrimitive?.contentOrNull
    if (nonce.isNullOrBlank() || !FleetProtocol.isValidNonce(nonce))
        return err(req.requestId, ErrorCategory.INVALID_PARAMETERS, "nonce must be a 128-bit hex or UUID")
    val timestampMs = params["timestampMs"]?.jsonPrimitive?.longOrNull
    if (timestampMs == null)
        return err(req.requestId, ErrorCategory.INVALID_PARAMETERS, "timestampMs is required")
    val ttl =
        try {
          resolveTtlFromParams(params)
        } catch (e: InvalidParametersException) {
          return err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid ttlMs")
        }
    val now = clock()
    if (isFutureSkew(timestampMs, now))
        return err(req.requestId, ErrorCategory.EXPIRED, "timestampMs is more than 120s in the future")
    if (isExpired(timestampMs, ttl, now))
        return err(req.requestId, ErrorCategory.EXPIRED, "envelope expired")
    if (nonceCache.seen(sourceNodeRef, nonce, now))
        return err(req.requestId, ErrorCategory.REPLAY_DETECTED, "nonce has already been used")
    return null
  }

  private fun handleEnvelope(
      raw: String?,
      controlEnabled: Boolean,
      sourceNodeRef: String,
  ): BridgeResponse {
    val env =
        try {
          FleetProtocol.json.decodeFromString(RemoteEnvelope.serializer(), raw ?: "")
        } catch (_: Exception) {
          return err(null, ErrorCategory.INVALID_PARAMETERS, "request is not a valid remote envelope")
        }
    if (env.protocolMajor != FleetProtocol.PROTOCOL_MAJOR)
        return err(
            env.requestId,
            ErrorCategory.INVALID_PARAMETERS,
            "unsupported protocol_major ${env.protocolMajor}; expected ${FleetProtocol.PROTOCOL_MAJOR}",
        )
    if (env.requestId.isBlank())
        return err(env.requestId, ErrorCategory.INVALID_PARAMETERS, "requestId must be non-empty")
    val target = env.targetNodeRef
    if (target.isBlank() || !FleetProtocol.isValidNodeId(target))
        return err(env.requestId, ErrorCategory.INVALID_PARAMETERS, "targetNodeRef is blank or malformed")
    val known = knownNodeRefs ?: setOf(nodeId)
    if (target !in known)
        return err(env.requestId, ErrorCategory.NOT_FOUND, "targetNodeRef is unknown or unresolvable")
    if (!pairing.isPaired(sourceNodeRef, target))
        return err(env.requestId, ErrorCategory.NOT_PAIRED, "targetNodeRef is not paired with this caller")
    if (!FleetProtocol.isValidNonce(env.nonce))
        return err(env.requestId, ErrorCategory.INVALID_PARAMETERS, "nonce must be a 128-bit hex or UUID")
    val ttl =
        try {
          resolveTtl(raw, env)
        } catch (e: InvalidParametersException) {
          return err(env.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid ttlMs")
        }
    val now = clock()
    if (isFutureSkew(env.timestampMs, now))
        return err(env.requestId, ErrorCategory.EXPIRED, "timestampMs is more than 120s in the future")
    if (isExpired(env.timestampMs, ttl, now))
        return err(env.requestId, ErrorCategory.EXPIRED, "envelope expired")
    if (nonceCache.seen(sourceNodeRef, env.nonce, now))
        return err(env.requestId, ErrorCategory.REPLAY_DETECTED, "nonce has already been used")
    val remoteOp = parseRemoteOp(env.op)
    if (remoteOp == null)
        return err(env.requestId, ErrorCategory.UNSUPPORTED_OP, "unsupported op")
    if (!controlEnabled && remoteOp != RemoteOp.CAPABILITIES_GET)
        return err(
            env.requestId,
            ErrorCategory.EXTERNAL_CONTROL_DISABLED,
            "external control is turned off in Victorious Mesh settings",
        )
    return try {
      when (remoteOp) {
        RemoteOp.CAPABILITIES_GET -> ok(env.requestId, capabilitiesResult())
        RemoteOp.REMOTE_COMMAND -> dispatchRemoteCommand(env, sourceNodeRef, ttl, now)
        RemoteOp.MACRO_STEP -> dispatchMacroStep(env, sourceNodeRef, ttl, now)
        RemoteOp.MACRO_RUN -> dispatchMacroRun(env, sourceNodeRef, ttl, now)
      }
    } catch (e: InvalidParametersException) {
      err(env.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      err(env.requestId, ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: SafetyRefused) {
      err(env.requestId, ErrorCategory.SAFETY_REFUSED, e.message ?: "refused")
    } catch (e: Exception) {
      err(env.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  private fun dispatchRemoteCommand(
      env: RemoteEnvelope,
      sourceNodeRef: String,
      ttl: Long,
      now: Long,
  ): BridgeResponse {
    if (env.actionRef.isBlank())
        throw InvalidParametersException("actionRef must be non-empty")
    if (env.actionRef == FleetProtocol.OP_NODE_STATUS) {
      // Cheap compatible alias: already authenticated + replay-checked.
      // Never enter confirmation gating.
      return ok(env.requestId, nodeStatusResult(env.params))
    }
    if (env.params.isNotEmpty())
        throw InvalidParametersException("operation ${env.actionRef} takes no parameters")
    return outcomeToResponse(
        env.requestId,
        executeAction(env.actionRef, env.requestId, sourceNodeRef, ttl, now, execute = true, params = env.params),
    )
  }

  private fun dispatchMacroStep(
      env: RemoteEnvelope,
      sourceNodeRef: String,
      ttl: Long,
      now: Long,
  ): BridgeResponse {
    val macroId = env.macroId?.takeIf { it.isNotBlank() }
        ?: throw InvalidParametersException("macroId must be non-empty")
    val stepIndex = env.stepIndex
        ?: throw InvalidParametersException("stepIndex is required")
    if (stepIndex < 0) throw InvalidParametersException("stepIndex must be >= 0")
    val onError = env.onError ?: FleetProtocol.ON_ERROR_ABORT
    if (onError != FleetProtocol.ON_ERROR_ABORT && onError != FleetProtocol.ON_ERROR_CONTINUE)
        throw InvalidParametersException("onError must be abort or continue")
    if (env.actionRef.isBlank()) throw InvalidParametersException("actionRef must be non-empty")
    if (env.params.isNotEmpty() && env.actionRef != FleetProtocol.OP_NODE_STATUS)
        throw InvalidParametersException("operation ${env.actionRef} takes no parameters")
    val outcome =
        executeAction(
            env.actionRef,
            env.requestId,
            sourceNodeRef,
            ttl,
            now,
            execute = true,
            params = env.params,
        )
    val result =
        buildJsonObject {
          put("macroId", macroId)
          put("stepIndex", stepIndex)
          put("onError", onError)
          put("actionRef", env.actionRef)
          putOutcome(outcome)
        }
    return when (outcome) {
      is ActionOutcome.Ok -> ok(env.requestId, result)
      is ActionOutcome.Err ->
          BridgeResponse(
              requestId = env.requestId,
              nodeId = nodeId,
              ok = false,
              result = result,
              error = BridgeError(outcome.category, outcome.message),
          )
    }
  }

  private fun dispatchMacroRun(
      env: RemoteEnvelope,
      sourceNodeRef: String,
      ttl: Long,
      now: Long,
  ): BridgeResponse {
    val macroId = env.macroId?.takeIf { it.isNotBlank() }
        ?: throw InvalidParametersException("macroId must be non-empty")
    val steps = env.steps ?: throw InvalidParametersException("steps is required")
    val execute = !env.dryRun
    val stepResults = ArrayList<JsonObject>(steps.size)
    var firstError: ActionOutcome.Err? = null
    var aborted = false
    for ((index, step) in steps.withIndex()) {
      if (step.actionRef.isBlank()) throw InvalidParametersException("steps[$index].actionRef must be non-empty")
      if (step.params.isNotEmpty() && step.actionRef != FleetProtocol.OP_NODE_STATUS)
          throw InvalidParametersException("operation ${step.actionRef} takes no parameters")
      if (step.onError != FleetProtocol.ON_ERROR_ABORT && step.onError != FleetProtocol.ON_ERROR_CONTINUE)
          throw InvalidParametersException("steps[$index].onError must be abort or continue")
      val outcome =
          executeAction(
              step.actionRef,
              env.requestId,
              sourceNodeRef,
              ttl,
              now,
              execute = execute,
              params = step.params,
          )
      stepResults.add(
          buildJsonObject {
            put("stepIndex", index)
            put("actionRef", step.actionRef)
            put("onError", step.onError)
            putOutcome(outcome)
          }
      )
      if (outcome is ActionOutcome.Err) {
        if (firstError == null) firstError = outcome
        if (step.onError == FleetProtocol.ON_ERROR_ABORT) {
          aborted = true
          break
        }
      }
    }
    val result =
        buildJsonObject {
          put("macroId", macroId)
          put("dryRun", env.dryRun)
          put("aborted", aborted)
          putJsonArray("steps") { stepResults.forEach { add(it) } }
        }
    val err = firstError
    return if (err == null) ok(env.requestId, result)
    else
        BridgeResponse(
            requestId = env.requestId,
            nodeId = nodeId,
            ok = false,
            result = result,
            error = BridgeError(err.category, err.message),
        )
  }

  private fun executeAction(
      actionRef: String,
      requestId: String,
      sourceNodeRef: String,
      ttl: Long,
      now: Long,
      execute: Boolean,
      params: JsonObject = JsonObject(emptyMap()),
  ): ActionOutcome {
    val cap = capabilities.find { it.name == actionRef }
    if (cap == null) return ActionOutcome.Err(ErrorCategory.NOT_FOUND, "unknown actionRef $actionRef")
    if (!cap.remoteAllowed)
        return ActionOutcome.Err(ErrorCategory.CAPABILITY_OFF, "action $actionRef is not remotely allowed")
    if (actionRef == FleetProtocol.OP_NODE_STATUS) {
      if (!execute) {
        return ActionOutcome.Ok(buildJsonObject { put("validated", true); put("actionRef", actionRef) })
      }
      return try {
        ActionOutcome.Ok(nodeStatusResult(params))
      } catch (e: InvalidParametersException) {
        ActionOutcome.Err(ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
      } catch (e: ActionNotFound) {
        ActionOutcome.Err(ErrorCategory.NOT_FOUND, e.message ?: "not found")
      } catch (e: Exception) {
        ActionOutcome.Err(ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
      }
    }
    if (cap.kind == FleetProtocol.KIND_DESTRUCTIVE || cap.requiresConfirm) {
      if (!execute) {
        return ActionOutcome.Ok(
            buildJsonObject {
              put("validated", true)
              put("confirmationRequired", true)
              put("actionRef", actionRef)
            }
        )
      }
      val pending =
          confirmations.create(
              actionRef = actionRef,
              requestId = requestId,
              sourceNodeRef = sourceNodeRef,
              expiresAtMs = now + ttl,
          )
      confirmationSink.onConfirmationRequired(pending)
      return ActionOutcome.Err(
          ErrorCategory.CONFIRMATION_REQUIRED,
          "destructive action requires on-phone confirmation",
      )
    }
    if (!execute) {
      return ActionOutcome.Ok(buildJsonObject { put("validated", true); put("actionRef", actionRef) })
    }
    return try {
      ActionOutcome.Ok(runMeshAction(actionRef, params))
    } catch (e: InvalidParametersException) {
      ActionOutcome.Err(ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: ActionNotFound) {
      ActionOutcome.Err(ErrorCategory.NOT_FOUND, e.message ?: "not found")
    } catch (e: SafetyRefused) {
      ActionOutcome.Err(ErrorCategory.SAFETY_REFUSED, e.message ?: "refused")
    } catch (e: Exception) {
      ActionOutcome.Err(ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  private fun outcomeToResponse(requestId: String, outcome: ActionOutcome): BridgeResponse =
      when (outcome) {
        is ActionOutcome.Ok -> ok(requestId, outcome.result)
        is ActionOutcome.Err -> err(requestId, outcome.category, outcome.message)
      }

  private fun capabilitiesResult(): JsonObject =
      buildJsonObject {
        putJsonArray("operations") {
          capabilities.forEach { cap ->
            add(
                buildJsonObject {
                  put("name", cap.name)
                  put("kind", cap.kind)
                  put("remoteAllowed", cap.remoteAllowed)
                  put("requiresConfirm", cap.requiresConfirm)
                }
            )
          }
        }
        put("protocol_major", FleetProtocol.PROTOCOL_MAJOR)
        put("protocol_minor", FleetProtocol.PROTOCOL_MINOR)
      }

  private class SafetyRefused(msg: String) : Exception(msg)

  private fun runMeshAction(op: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
      when (op) {
        FleetProtocol.OP_CAPABILITIES ->
            buildJsonObject {
              putJsonArray("operations") { FleetProtocol.OPERATIONS.forEach { add(JsonPrimitive(it)) } }
              put("dca_required", false)
              put("fleet_class", "external_node")
              put("protocol_major", FleetProtocol.PROTOCOL_MAJOR)
              put("protocol_minor", FleetProtocol.PROTOCOL_MINOR)
            }
        FleetProtocol.OP_STATUS -> {
          val s = backend.status().forTunnelMode(backend.wireGuardModeActive())
          buildJsonObject {
            put("backend_state", s.backendState)
            put("connected", s.connected)
            put("control_url", s.controlUrl)
            put("ips", JsonArray(listOfNotNull(s.ipv4, s.ipv6).map { JsonPrimitive(it) }))
          }
        }
        FleetProtocol.OP_UP -> {
          if (!backend.vpnConsentGranted())
              throw SafetyRefused(
                  "VPN permission not granted: the user must open the Victorious Mesh app once and approve the VPN"
              )
          if (backend.wireGuardModeActive())
              throw SafetyRefused(
                  "WireGuard mode is active on this device; the user must switch back to mesh mode in the app"
              )
          backend.up()
          buildJsonObject { put("requested", "up") }
        }
        FleetProtocol.OP_DOWN -> {
          // WireGuard mode already reports the mesh as down; do not tear the
          // WireGuard tunnel down from a mesh.down (no new fleet ops).
          if (!backend.wireGuardModeActive()) {
            backend.down()
          }
          buildJsonObject { put("requested", "down") }
        }
        FleetProtocol.OP_IP_GET -> {
          val s = backend.status().forTunnelMode(backend.wireGuardModeActive())
          buildJsonObject {
            put("ipv4", s.ipv4)
            put("ipv6", s.ipv6)
          }
        }
        FleetProtocol.OP_NODE_STATUS -> nodeStatusResult(params)
        FleetProtocol.OP_CONFIG_EXPORT,
        FleetProtocol.OP_CONFIG_IMPORT ->
            throw InvalidParametersException("operation $op is not a remote.command actionRef")
        else -> throw InvalidParametersException("unsupported mesh action $op")
      }

  private fun exportConfig(params: JsonObject): JsonObject {
    val asked = BackupArgs.string(params, "packageName")
    if (asked != null && asked != packageName) {
      throw ActionNotFound("named packageName has no entry")
    }
    if (BackupArgs.string(params, "clearPayload") == "false") {
      throw InvalidParametersException("encrypted export is not supported on this node")
    }
    val payload = configStore.snapshot()
    val entry = UnifiedBackupCodec.thisNodeEntry(payload, packageName)
    return UnifiedBackupCodec.encodeEntry(entry)
  }

  private fun importConfig(req: BridgeRequest, sourceNodeRef: String): BridgeResponse {
    val params = req.parameters
    val dryRun = BackupArgs.bool(params, "dryRun")
    val policy = BackupConflictPolicy.fromWire(BackupArgs.string(params, "conflictPolicy"))
    val asked = BackupArgs.string(params, "packageName") ?: packageName
    val bundle = parseImportBundle(params)
    val entry = UnifiedBackupCodec.entryForPackage(bundle, asked)
    if (entry.packageName != packageName) {
      throw ActionNotFound("named packageName has no entry")
    }
    val clear = UnifiedBackupCodec.unwrapClearPayload(bundle, entry)
    val imported = UnifiedBackupCodec.decodePayload(clear)
    val plan = BackupImporter.plan(configStore.snapshot(), imported, policy, dryRun)
    val result = BackupImporter.encodeResult(plan)
    if (dryRun) return ok(req.requestId, result)
    val ttl = resolveTtlFromParams(params)
    val pending =
        confirmations.create(
            actionRef = FleetProtocol.OP_CONFIG_IMPORT,
            requestId = req.requestId,
            sourceNodeRef = sourceNodeRef,
            expiresAtMs = clock() + ttl,
            importPayloadJson = UnifiedBackupCodec.encodePayload(plan.applied),
            importResult = result,
        )
    confirmationSink.onConfirmationRequired(pending)
    return err(
        req.requestId,
        ErrorCategory.CONFIRMATION_REQUIRED,
        "config.import requires on-phone confirmation",
    )
  }

  private fun parseImportBundle(params: JsonObject): UnifiedFleetBackup {
    val asObject = BackupArgs.jsonObject(params, "bundle")
    if (asObject != null) return UnifiedBackupCodec.decodeBundle(asObject)
    val asString = BackupArgs.string(params, "bundle")
        ?: throw InvalidParametersException("bundle is required")
    return UnifiedBackupCodec.decodeBundle(asString)
  }

  private fun applyPendingImport(pending: PendingConfirmation): JsonObject {
    val json = pending.importPayloadJson
        ?: throw InvalidParametersException("pending import is missing a payload")
    val payload = UnifiedBackupCodec.decodePayload(json)
    configStore.apply(payload)
    configStore.setExternalControlEnabled(false)
    return pending.importResult ?: BackupImporter.encodeResult(
        ImportPlan(
            dryRun = false,
            conflictPolicy = BackupConflictPolicy.SKIP,
            changes = emptyList(),
            applied = payload,
        )
    )
  }

  private fun nodeStatusResult(params: JsonObject): JsonObject {
    resolveStatusTarget(params)
    val status =
        try {
          backend.status()
        } catch (_: Exception) {
          null
        }
    val snapshot =
        NodeStatusBuilder.fromMesh(
            nodeId = nodeId,
            packageName = packageName,
            status = status,
            wireGuardActive = backend.wireGuardModeActive(),
            vpnConsentGranted = backend.vpnConsentGranted(),
            updatedAtMs = clock(),
        )
    return NodeStatusSnapshotCodec.encode(snapshot)
  }

  /**
   * Optional `args.nodeId`. Unknown parameter (and args) keys are ignored for
   * forward compatibility. Only a present but blank/malformed nodeId is
   * `invalid_parameters`. A well-formed id for another node is `not_found`.
   */
  private fun resolveStatusTarget(params: JsonObject) {
    val args = params["args"] as? JsonObject ?: return
    val raw = args["nodeId"] ?: return
    val asked = raw.jsonPrimitive.contentOrNull?.trim().orEmpty()
    if (asked.isBlank() || !FleetProtocol.isValidNodeId(asked)) {
      throw InvalidParametersException("args.nodeId is blank or malformed")
    }
    if (asked != nodeId) throw ActionNotFound("args.nodeId does not match this node")
  }

  private fun resolveTtlFromParams(params: JsonObject): Long {
    val ttl = params["ttlMs"] ?: return FleetProtocol.DEFAULT_TTL_MS
    val n = (ttl as? JsonPrimitive)?.longOrNull
        ?: throw InvalidParametersException("ttlMs must be an integer")
    if (n <= 0) throw InvalidParametersException("ttlMs must be positive")
    return FleetProtocol.clampTtl(n)
  }

  private fun ok(id: String?, result: JsonObject) =
      BridgeResponse(requestId = id, nodeId = nodeId, ok = true, result = result)

  private fun err(id: String?, cat: String, msg: String, withNode: Boolean = true) =
      BridgeResponse(
          requestId = id,
          nodeId = if (withNode) nodeId else null,
          ok = false,
          error = BridgeError(cat, msg),
      )

  companion object {
    /** Split "100.1.2.3/32" style prefixes into (ipv4, ipv6). */
    fun splitAddresses(prefixes: List<String>?): Pair<String?, String?> {
      val ips = prefixes.orEmpty().map { it.substringBefore('/') }
      return ips.firstOrNull { !it.contains(':') } to ips.firstOrNull { it.contains(':') }
    }

    fun isExpired(timestampMs: Long, ttlMs: Long, nowMs: Long): Boolean =
        nowMs > timestampMs + ttlMs

    fun isFutureSkew(timestampMs: Long, nowMs: Long): Boolean =
        timestampMs > nowMs + FleetProtocol.MAX_FUTURE_SKEW_MS

    private fun resolveTtl(raw: String?, env: RemoteEnvelope): Long {
      val explicit = peekTtlElement(raw)
      if (explicit != null) {
        val n = explicit.longOrNull ?: throw InvalidParametersException("ttlMs must be an integer")
        if (n <= 0) throw InvalidParametersException("ttlMs must be positive")
        return FleetProtocol.clampTtl(n)
      }
      val n = env.ttlMs ?: return FleetProtocol.DEFAULT_TTL_MS
      if (n <= 0) throw InvalidParametersException("ttlMs must be positive")
      return FleetProtocol.clampTtl(n)
    }

    private fun peekTtlElement(raw: String?): JsonPrimitive? {
      val el =
          try {
            FleetProtocol.json.parseToJsonElement(raw ?: "")
          } catch (_: Exception) {
            return null
          }
      if (el !is JsonObject) return null
      val ttl: JsonElement = el["ttlMs"] ?: return null
      return ttl as? JsonPrimitive
    }

    private fun JsonObjectBuilder.putOutcome(outcome: ActionOutcome) {
      when (outcome) {
        is ActionOutcome.Ok -> {
          put("ok", true)
          put("result", outcome.result)
        }
        is ActionOutcome.Err -> {
          put("ok", false)
          put(
              "error",
              buildJsonObject {
                put("category", outcome.category)
                put("message", outcome.message)
              },
          )
        }
      }
    }
  }
}

private sealed class ActionOutcome {
  data class Ok(val result: JsonObject) : ActionOutcome()

  data class Err(val category: String, val message: String) : ActionOutcome()
}
