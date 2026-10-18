// Victorious Mesh
// Fleet IPC bridge: pure-Kotlin protocol core (no Android deps, JVM unit-testable).
// Spec: EXTERNAL_NODE spec relayed by Fleet Orchestrator 2026-10-18.

package com.tailscale.ipn.fleet

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object FleetProtocol {
  const val PROTOCOL_MAJOR = 1
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
  val OPERATIONS = listOf(OP_CAPABILITIES, OP_STATUS, OP_UP, OP_DOWN, OP_IP_GET)

  val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun newNodeId(random: SecureRandom = SecureRandom()): String {
    val b = ByteArray(16).also { random.nextBytes(it) }
    return NODE_ID_PREFIX + b.joinToString("") { "%02x".format(it) }
  }

  fun isValidNodeId(id: String) = NODE_ID_REGEX.matches(id)
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

@Serializable data class BridgeError(val category: String, val message: String)

@Serializable
data class BridgeProgress(val phase: String, val done: Int, val total: Int)

@Serializable
data class BridgeResponse(
    @SerialName("protocol_major") val protocolMajor: Int = FleetProtocol.PROTOCOL_MAJOR,
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
}

class InvalidParametersException(msg: String) : Exception(msg)

/** Signing-cert allowlist for controller apps. */
object CallerAllowlist {
  private const val DCA_DEBUG_CERT =
      "88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D"

  // ===================================================================================
  // TODO(release cert): droidcommand-AI RELEASE signing cert SHA-256 is TBD per spec.
  // Do NOT add a guessed value. Once confirmed, add e.g.:
  //   "ai.droidcommand.app" to setOf(DCA_DEBUG_CERT, DCA_RELEASE_CERT),
  // ===================================================================================

  val ENTRIES: Map<String, Set<String>> =
      mapOf(
          "ai.droidcommand.app" to setOf(normalize(DCA_DEBUG_CERT)),
          "ai.droidcommand.app.debug" to setOf(normalize(DCA_DEBUG_CERT)),
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
 * Dispatches a raw request string. Check order:
 * 1. caller allowlist -> unauthorized_caller
 * 2. external control setting -> external_control_disabled
 * 3. JSON parse -> malformed_request
 * 4. protocol_major -> protocol_mismatch
 * 5. target_node_id -> node_mismatch
 * 6. operation -> unsupported_operation / invalid_parameters / safety_refused / internal
 */
class FleetDispatcher(private val nodeId: String, private val backend: MeshBackend) {

  fun handle(raw: String?, callerAllowed: Boolean, controlEnabled: Boolean): BridgeResponse {
    if (!callerAllowed)
        return err(null, ErrorCategory.UNAUTHORIZED_CALLER, "caller is not an allowlisted controller", withNode = false)
    if (!controlEnabled)
        return err(
            null,
            ErrorCategory.EXTERNAL_CONTROL_DISABLED,
            "external control is turned off in Victorious Mesh settings",
        )
    val req =
        try {
          FleetProtocol.json.decodeFromString(BridgeRequest.serializer(), raw ?: "")
        } catch (e: Exception) {
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
    return try {
      if (req.operation !in FleetProtocol.OPERATIONS)
          return err(req.requestId, ErrorCategory.UNSUPPORTED_OPERATION, "unsupported operation")
      if (req.parameters.isNotEmpty())
          throw InvalidParametersException("operation ${req.operation} takes no parameters")
      if (req.timeoutMs <= 0) throw InvalidParametersException("timeout_ms must be positive")
      ok(req.requestId, run(req.operation))
    } catch (e: InvalidParametersException) {
      err(req.requestId, ErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid parameters")
    } catch (e: SafetyRefused) {
      err(req.requestId, ErrorCategory.SAFETY_REFUSED, e.message ?: "refused")
    } catch (e: Exception) {
      // Do not leak exception details (could contain paths/state); class name only.
      err(req.requestId, ErrorCategory.INTERNAL, "internal error: ${e.javaClass.simpleName}")
    }
  }

  private class SafetyRefused(msg: String) : Exception(msg)

  private fun run(op: String): JsonObject =
      when (op) {
        FleetProtocol.OP_CAPABILITIES ->
            buildJsonObject {
              putJsonArray("operations") { FleetProtocol.OPERATIONS.forEach { add(JsonPrimitive(it)) } }
              put("dca_required", false)
              put("fleet_class", "external_node")
              put("protocol_major", FleetProtocol.PROTOCOL_MAJOR)
            }
        FleetProtocol.OP_STATUS -> {
          val s = backend.status()
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
          backend.up()
          buildJsonObject { put("requested", "up") }
        }
        FleetProtocol.OP_DOWN -> {
          backend.down()
          buildJsonObject { put("requested", "down") }
        }
        FleetProtocol.OP_IP_GET -> {
          val s = backend.status()
          buildJsonObject {
            put("ipv4", s.ipv4)
            put("ipv6", s.ipv6)
          }
        }
        else -> throw IllegalStateException("unreachable")
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
  }
}
