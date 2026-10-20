// Victorious Mesh
// Frozen STATUS-TRAY snapshot (docs/specs/STATUS_TRAY.md in droidcommand-AI #235).
// JVM-testable: no Android imports.

package com.tailscale.ipn.fleet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class StatusTrayDomain {
  MESH,
  VPN,
  BROWSER,
  SMS,
  NODE_HEALTH,
  OTHER;

  val wire: String
    get() =
        when (this) {
          MESH -> "mesh"
          VPN -> "vpn"
          BROWSER -> "browser"
          SMS -> "sms"
          NODE_HEALTH -> "node_health"
          OTHER -> "other"
        }

  companion object {
    fun fromWire(raw: String): StatusTrayDomain =
        when (raw) {
          "mesh" -> MESH
          "vpn" -> VPN
          "browser" -> BROWSER
          "sms" -> SMS
          "node_health" -> NODE_HEALTH
          "other" -> OTHER
          else -> OTHER
        }
  }
}

enum class StatusTrayState {
  OK,
  DEGRADED,
  DOWN,
  UNKNOWN,
  OFF;

  val wire: String
    get() =
        when (this) {
          OK -> "ok"
          DEGRADED -> "degraded"
          DOWN -> "down"
          UNKNOWN -> "unknown"
          OFF -> "off"
        }

  companion object {
    fun fromWire(raw: String): StatusTrayState =
        when (raw) {
          "ok" -> OK
          "degraded" -> DEGRADED
          "down" -> DOWN
          "unknown" -> UNKNOWN
          "off" -> OFF
          else -> UNKNOWN
        }
  }
}

data class StatusTrayDomainStatus(
    val name: StatusTrayDomain,
    val state: StatusTrayState,
    val detail: String? = null,
    /** Original wire name when [name] decoded as OTHER. */
    val rawName: String? = null,
)

data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String?,
    val connectionClass: String,
    val updatedAtMs: Long,
    val domains: List<StatusTrayDomainStatus>,
) {
  companion object {
    const val CONNECTION_EXTERNAL_NODE = "EXTERNAL_NODE"
    const val CONNECTION_BOUND_COMPANION = "BOUND_COMPANION"
    const val CONNECTION_TERMUX_IPC = "TERMUX_IPC"
    const val CONNECTION_HOST_PROCESS_TOOL = "HOST_PROCESS_TOOL"
    const val DETAIL_MAX_CHARS = 200
    const val PACKAGE_VICTORIOUS_MESH = "com.victorious.mesh"
  }
}

object NodeStatusSnapshotCodec {
  private val FORBIDDEN =
      Regex(
          """(?i)(?<![A-Za-z0-9_])(password|api[_-]?key|api[_-]?token|token|secret|code[_-]?word|whitelist|signing[_-]?key|private[_-]?key)(?![A-Za-z0-9_])"""
      )
  private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
  private val IPV6 = Regex("""(?i)\b(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}\b""")

  fun encode(snapshot: NodeStatusSnapshot): JsonObject {
    if (snapshot.nodeId.isBlank()) {
      throw InvalidParametersException("nodeId must be non-blank")
    }
    return buildJsonObject {
      put("nodeId", snapshot.nodeId)
      if (snapshot.packageName == null) put("packageName", JsonNull)
      else put("packageName", snapshot.packageName)
      put("connectionClass", snapshot.connectionClass)
      put("updatedAtMs", snapshot.updatedAtMs)
      putJsonArray("domains") {
        snapshot.domains.forEach { domain -> add(encodeDomain(domain)) }
      }
    }
  }

  fun decode(obj: JsonObject): NodeStatusSnapshot {
    val nodeId = obj["nodeId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (nodeId.isBlank()) throw InvalidParametersException("nodeId must be non-blank")
    val packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull
    val connectionClass =
        obj["connectionClass"]?.jsonPrimitive?.contentOrNull
            ?: throw InvalidParametersException("connectionClass is required")
    val updatedAtMs =
        obj["updatedAtMs"]?.jsonPrimitive?.long
            ?: throw InvalidParametersException("updatedAtMs is required")
    val domainsEl = obj["domains"]
    val domains =
        if (domainsEl is JsonArray) {
          domainsEl.map { decodeDomain(it.jsonObject) }
        } else {
          emptyList()
        }
    return NodeStatusSnapshot(
        nodeId = nodeId,
        packageName = packageName,
        connectionClass = connectionClass,
        updatedAtMs = updatedAtMs,
        domains = domains,
    )
  }

  fun validateDetail(detail: String?) {
    if (detail == null) return
    if (detail.length > NodeStatusSnapshot.DETAIL_MAX_CHARS) {
      throw InvalidParametersException(
          "detail exceeds ${NodeStatusSnapshot.DETAIL_MAX_CHARS} characters"
      )
    }
    if (FORBIDDEN.containsMatchIn(detail) || IPV4.containsMatchIn(detail) || IPV6.containsMatchIn(detail)) {
      throw InvalidParametersException("detail contains forbidden privacy content")
    }
  }

  private fun encodeDomain(domain: StatusTrayDomainStatus): JsonObject {
    validateDetail(domain.detail)
    val name =
        if (domain.name == StatusTrayDomain.OTHER && !domain.rawName.isNullOrBlank()) domain.rawName
        else domain.name.wire
    return buildJsonObject {
      put("name", name)
      put("state", domain.state.wire)
      val d = domain.detail?.takeIf { it.isNotBlank() }
      if (d != null) put("detail", d)
    }
  }

  private fun decodeDomain(obj: JsonObject): StatusTrayDomainStatus {
    val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: "other"
    val name = StatusTrayDomain.fromWire(rawName)
    val state = StatusTrayState.fromWire(obj["state"]?.jsonPrimitive?.contentOrNull ?: "unknown")
    val detail = obj["detail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    validateDetail(detail)
    return StatusTrayDomainStatus(
        name = name,
        state = state,
        detail = detail,
        rawName = if (name == StatusTrayDomain.OTHER) rawName else null,
    )
  }
}

object NodeStatusBuilder {
  fun fromMesh(
      nodeId: String,
      packageName: String,
      status: MeshStatus?,
      wireGuardActive: Boolean,
      vpnConsentGranted: Boolean,
      updatedAtMs: Long,
  ): NodeStatusSnapshot {
    val mesh = meshDomain(status, wireGuardActive)
    val vpn = vpnDomain(status, wireGuardActive, vpnConsentGranted)
    val health =
        if (status == null) {
          StatusTrayDomainStatus(
              StatusTrayDomain.NODE_HEALTH,
              StatusTrayState.DEGRADED,
              "status read failed",
          )
        } else {
          StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK)
        }
    return NodeStatusSnapshot(
        nodeId = nodeId,
        packageName = packageName,
        connectionClass = NodeStatusSnapshot.CONNECTION_EXTERNAL_NODE,
        updatedAtMs = updatedAtMs,
        domains = listOf(mesh, vpn, health),
    )
  }

  private fun meshDomain(status: MeshStatus?, wireGuardActive: Boolean): StatusTrayDomainStatus {
    if (wireGuardActive) {
      return StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.DOWN, "WireGuard mode")
    }
    if (status == null) {
      return StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.UNKNOWN, "mesh unavailable")
    }
    return if (status.connected) {
      StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.OK, "headscale up")
    } else if (status.backendState == "Starting") {
      StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.DEGRADED, "mesh starting")
    } else {
      StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.DOWN, "mesh down")
    }
  }

  private fun vpnDomain(
      status: MeshStatus?,
      wireGuardActive: Boolean,
      vpnConsentGranted: Boolean,
  ): StatusTrayDomainStatus {
    if (wireGuardActive) {
      return StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.OK, "WireGuard up")
    }
    if (!vpnConsentGranted) {
      return StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.OFF, "VPN permission needed")
    }
    if (status == null) {
      return StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.UNKNOWN, "tunnel unknown")
    }
    return if (status.connected) {
      StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.OK, "mesh tunnel up")
    } else {
      StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.DOWN, "tunnel down")
    }
  }
}
