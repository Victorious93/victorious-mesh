// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.meshhealth

import java.util.Locale

/**
 * On-demand mesh speed / health snapshot. Nothing runs until the user taps
 * Run check.
 *
 * Speed here is path quality (direct vs relay) plus lifetime engine
 * transfer — not a third-party internet download test. Latency is one
 * disco ping to the first online IPv4 peer.
 */
enum class MeshHealthGrade {
  OK,
  DEGRADED,
  DOWN,
}

enum class MeshHealthSeverity {
  OK,
  WARN,
  FAIL,
}

enum class MeshPathQuality {
  NONE,
  ALL_DIRECT,
  MIXED,
  ALL_RELAY,
}

data class MeshPeerSnapshot(
    val online: Boolean,
    val curAddr: String = "",
    val relay: String = "",
    val peerRelay: String = "",
    val ipv4: String? = null,
) {
  val isDirect: Boolean
    get() = online && curAddr.isNotEmpty()

  val isRelay: Boolean
    get() = online && curAddr.isEmpty() && (relay.isNotEmpty() || peerRelay.isNotEmpty())
}

data class MeshPeerPathCounts(
    val total: Int,
    val online: Int,
    val direct: Int,
    val relay: Int,
)

data class MeshHealthInputs(
    val vpnActive: Boolean,
    val wireGuardActive: Boolean,
    val backendState: String,
    val warningTitles: List<String>,
    val peers: List<MeshPeerSnapshot>,
    val rBytes: Long,
    val wBytes: Long,
    val numLive: Int,
    val latencyMs: Double?,
    val latencyError: String?,
    val pingTarget: String?,
    val statusError: String? = null,
)

data class MeshHealthFinding(
    val id: String,
    val severity: MeshHealthSeverity,
    val detail: String,
)

data class MeshHealthReport(
    val grade: MeshHealthGrade,
    val path: MeshPathQuality,
    val peerCounts: MeshPeerPathCounts,
    val findings: List<MeshHealthFinding>,
)

object MeshHealthPlanner {
  const val ID_GRADE = "grade"
  const val ID_VPN = "vpn"
  const val ID_MODE = "mode"
  const val ID_BACKEND = "backend"
  const val ID_WARNINGS = "warnings"
  const val ID_PATH = "path"
  const val ID_LATENCY = "latency"
  const val ID_TRANSFER = "transfer"
  const val ID_SCOPE = "scope"

  const val BACKEND_RUNNING = "Running"
  const val LATENCY_WARN_MS = 250.0

  fun counts(peers: List<MeshPeerSnapshot>): MeshPeerPathCounts {
    val online = peers.count { it.online }
    return MeshPeerPathCounts(
        total = peers.size,
        online = online,
        direct = peers.count { it.isDirect },
        relay = peers.count { it.isRelay },
    )
  }

  fun pathQuality(counts: MeshPeerPathCounts): MeshPathQuality =
      when {
        counts.online == 0 -> MeshPathQuality.NONE
        counts.direct == counts.online -> MeshPathQuality.ALL_DIRECT
        counts.direct == 0 -> MeshPathQuality.ALL_RELAY
        else -> MeshPathQuality.MIXED
      }

  fun firstOnlineIPv4(peers: List<MeshPeerSnapshot>): String? =
      peers.firstOrNull { it.online && !it.ipv4.isNullOrBlank() }?.ipv4

  fun ipv4FromAddresses(addresses: List<String>?): String? =
      addresses?.firstOrNull { isIPv4(it) }?.substringBefore('/')

  fun isIPv4(value: String): Boolean {
    val host = value.substringBefore('/')
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
  }

  fun formatBytes(n: Long): String {
    if (n < 1024L) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
  }

  fun formatLatency(ms: Double): String = String.format(Locale.US, "%.1f ms", ms)

  fun report(input: MeshHealthInputs): MeshHealthReport {
    val peerCounts = counts(input.peers)
    val path = pathQuality(peerCounts)
    val grade = grade(input, peerCounts)
    return MeshHealthReport(
        grade = grade,
        path = path,
        peerCounts = peerCounts,
        findings =
            listOf(
                gradeFinding(grade),
                vpnFinding(input),
                modeFinding(input),
                backendFinding(input),
                warningFinding(input),
                pathFinding(path, peerCounts),
                latencyFinding(input),
                transferFinding(input),
                MeshHealthFinding(
                    ID_SCOPE,
                    MeshHealthSeverity.WARN,
                    "Not an internet speed test. Latency is one disco ping to a mesh peer. Transfer is lifetime tunnel bytes.",
                ),
            ),
    )
  }

  internal fun grade(input: MeshHealthInputs, peerCounts: MeshPeerPathCounts): MeshHealthGrade {
    if (!input.vpnActive) return MeshHealthGrade.DOWN
    if (input.wireGuardActive) return MeshHealthGrade.DOWN
    if (input.statusError != null) return MeshHealthGrade.DOWN
    if (input.backendState != BACKEND_RUNNING) return MeshHealthGrade.DOWN
    if (input.warningTitles.isNotEmpty()) return MeshHealthGrade.DEGRADED
    if (peerCounts.online == 0) return MeshHealthGrade.DEGRADED
    if (peerCounts.direct == 0) return MeshHealthGrade.DEGRADED
    return MeshHealthGrade.OK
  }

  private fun gradeFinding(grade: MeshHealthGrade): MeshHealthFinding {
    val (severity, detail) =
        when (grade) {
          MeshHealthGrade.OK -> MeshHealthSeverity.OK to "Mesh health is OK"
          MeshHealthGrade.DEGRADED -> MeshHealthSeverity.WARN to "Mesh health is degraded"
          MeshHealthGrade.DOWN -> MeshHealthSeverity.FAIL to "Mesh is down"
        }
    return MeshHealthFinding(ID_GRADE, severity, detail)
  }

  private fun vpnFinding(input: MeshHealthInputs): MeshHealthFinding =
      if (input.vpnActive) {
        MeshHealthFinding(ID_VPN, MeshHealthSeverity.OK, "VPN is up")
      } else {
        MeshHealthFinding(ID_VPN, MeshHealthSeverity.FAIL, "VPN is down")
      }

  private fun modeFinding(input: MeshHealthInputs): MeshHealthFinding =
      if (input.wireGuardActive) {
        MeshHealthFinding(
            ID_MODE,
            MeshHealthSeverity.FAIL,
            "WireGuard mode is on; mesh health does not apply",
        )
      } else {
        MeshHealthFinding(ID_MODE, MeshHealthSeverity.OK, "Mesh mode")
      }

  private fun backendFinding(input: MeshHealthInputs): MeshHealthFinding {
    val err = input.statusError
    if (err != null) {
      return MeshHealthFinding(ID_BACKEND, MeshHealthSeverity.FAIL, "Status check failed: $err")
    }
    return if (input.backendState == BACKEND_RUNNING) {
      MeshHealthFinding(ID_BACKEND, MeshHealthSeverity.OK, "Backend is Running")
    } else {
      val state = input.backendState.ifBlank { "unknown" }
      MeshHealthFinding(ID_BACKEND, MeshHealthSeverity.FAIL, "Backend is $state")
    }
  }

  private fun warningFinding(input: MeshHealthInputs): MeshHealthFinding {
    val titles = input.warningTitles
    return if (titles.isEmpty()) {
      MeshHealthFinding(ID_WARNINGS, MeshHealthSeverity.OK, "No backend health warnings")
    } else {
      MeshHealthFinding(
          ID_WARNINGS,
          MeshHealthSeverity.WARN,
          "${titles.size} health warning(s): ${titles.joinToString("; ")}",
      )
    }
  }

  private fun pathFinding(path: MeshPathQuality, counts: MeshPeerPathCounts): MeshHealthFinding {
    val mix = "${counts.online} online / ${counts.direct} direct / ${counts.relay} relay"
    val (severity, label) =
        when (path) {
          MeshPathQuality.ALL_DIRECT -> MeshHealthSeverity.OK to "All online peers are direct"
          MeshPathQuality.MIXED -> MeshHealthSeverity.OK to "Mixed direct and relay paths"
          MeshPathQuality.ALL_RELAY -> MeshHealthSeverity.WARN to "Online peers are relayed"
          MeshPathQuality.NONE -> MeshHealthSeverity.WARN to "No online peers"
        }
    return MeshHealthFinding(ID_PATH, severity, "$label ($mix of ${counts.total} peers)")
  }

  private fun latencyFinding(input: MeshHealthInputs): MeshHealthFinding {
    val err = input.latencyError
    if (err != null) {
      return MeshHealthFinding(ID_LATENCY, MeshHealthSeverity.WARN, "Disco ping failed: $err")
    }
    val ms = input.latencyMs
    val target = input.pingTarget
    if (ms == null) {
      return if (target.isNullOrBlank()) {
        MeshHealthFinding(ID_LATENCY, MeshHealthSeverity.WARN, "No online IPv4 peer to ping")
      } else {
        MeshHealthFinding(ID_LATENCY, MeshHealthSeverity.WARN, "Disco ping not run")
      }
    }
    val label = "${formatLatency(ms)} disco ping to $target"
    return if (ms >= LATENCY_WARN_MS) {
      MeshHealthFinding(ID_LATENCY, MeshHealthSeverity.WARN, "$label (high)")
    } else {
      MeshHealthFinding(ID_LATENCY, MeshHealthSeverity.OK, label)
    }
  }

  private fun transferFinding(input: MeshHealthInputs): MeshHealthFinding {
    val down = formatBytes(input.rBytes)
    val up = formatBytes(input.wBytes)
    return MeshHealthFinding(
        ID_TRANSFER,
        MeshHealthSeverity.OK,
        "Lifetime $down down / $up up · ${input.numLive} live peer(s)",
    )
  }
}
