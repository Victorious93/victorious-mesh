// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.leakcheck

/**
 * On-demand leak report. Nothing runs until the user taps Run check
 * (off by default: no boot or background scan).
 *
 * This cannot prove other apps are leak-free. WebRTC / STUN, IPv6
 * bypass, and sockets opened before the VPN are out of scope.
 */
enum class LeakSeverity {
  OK,
  WARN,
  FAIL,
  /** Nothing was measured. Never shown or counted as a pass. */
  INCONCLUSIVE,
}

/** What a finding rests on. Configuration can never prove behaviour. */
enum class LeakEvidence {
  /** Read from settings: says what should happen, not what does. */
  CONFIGURATION,
  /** Actually seen, but only on this app's own path. */
  OBSERVED,
  /** A direct measurement of the question (an observation service saw or did not see the DNS query). */
  VERIFIED,
}

data class LeakFinding(
    val id: String,
    val severity: LeakSeverity,
    val detail: String,
    val evidence: LeakEvidence = LeakEvidence.CONFIGURATION,
)

data class LeakInputs(
    val vpnActive: Boolean,
    val killSwitchOn: Boolean,
    val directPackageCount: Int,
    val publicIp: String?,
    val publicIpIsMesh: Boolean,
    val sightingError: String?,
    val corpDns: Boolean?,
    /** The forced proxy is on but not fail-closed. */
    val forcedProxyFailOpen: Boolean = false,
    /** Per-network DNS rules exist but are not being applied right now. */
    val perNetworkDnsIgnored: Boolean = false,
    /** A Tor / I2P backend is required but cannot be reached right now. */
    val overlayDown: String? = null,
)

object LeakCheckerPlanner {
  /** Only HTTPS endpoints: a plain-http answer could be forged on the path. */
  fun endpointAllowed(url: String): Boolean =
      runCatching { java.net.URI(url.trim()) }.getOrNull()?.let {
        it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()
      } ?: false

  const val ID_VPN = "vpn"
  const val ID_KILLSWITCH = "killswitch"
  const val ID_DIRECT = "direct"
  const val ID_SIGHTING = "sighting"
  const val ID_DNS = "dns"
  const val ID_SCOPE = "scope"
  const val ID_DNS_OBSERVED = "dns-observed"
  const val ID_PNDNS = "pndns"
  const val ID_PROXY_OPEN = "proxy-failopen"
  const val ID_OVERLAY = "overlay"

  fun report(input: LeakInputs): List<LeakFinding> {
    return listOfNotNull(
        vpnFinding(input),
        killSwitchFinding(input),
        directFinding(input),
        sightingFinding(input),
        dnsFinding(input),
        if (input.forcedProxyFailOpen)
            LeakFinding(ID_PROXY_OPEN, LeakSeverity.WARN, "The forced proxy is not fail-closed: if it is down, app TCP goes directly")
        else null,
        if (input.perNetworkDnsIgnored)
            LeakFinding(ID_PNDNS, LeakSeverity.WARN, "Per-network DNS rules exist but are NOT being applied (the forced proxy is on)")
        else null,
        input.overlayDown?.let {
          LeakFinding(ID_OVERLAY, LeakSeverity.WARN, "$it is required but not reachable: matching traffic is blocked, not leaked")
        },
        LeakFinding(
            ID_SCOPE,
            LeakSeverity.WARN,
            "Cannot test other apps, their own encrypted DNS, WebRTC/STUN, IPv6 outside the tunnel, or sockets opened before the VPN",
        ),
    )
  }

  /**
   * The headline. It is only ever "pass-like" after a DNS observation test
   * actually passed; configuration alone is inconclusive.
   */
  fun overall(findings: List<LeakFinding>): Pair<LeakSeverity, String> {
    val observed = findings.firstOrNull { it.id == ID_DNS_OBSERVED }
    return when {
      findings.any { it.severity == LeakSeverity.FAIL } -> LeakSeverity.FAIL to "A check failed."
      observed == null || observed.severity == LeakSeverity.INCONCLUSIVE ->
          LeakSeverity.INCONCLUSIVE to "Settings were inspected, but DNS leakage was not measured. This is not a clean bill of health."
      findings.any { it.severity == LeakSeverity.WARN } -> LeakSeverity.WARN to "The observed DNS test passed; some warnings remain."
      else -> LeakSeverity.OK to "The observed DNS test passed. Other apps' own encrypted DNS and WebRTC are still not tested."
    }
  }

  private fun vpnFinding(input: LeakInputs): LeakFinding =
      if (input.vpnActive) {
        LeakFinding(ID_VPN, LeakSeverity.OK, "VPN is up")
      } else {
        LeakFinding(ID_VPN, LeakSeverity.FAIL, "VPN is down; traffic uses Wi-Fi or cellular")
      }

  private fun killSwitchFinding(input: LeakInputs): LeakFinding =
      if (input.killSwitchOn) {
        LeakFinding(ID_KILLSWITCH, LeakSeverity.OK, "Kill switch is on")
      } else {
        LeakFinding(ID_KILLSWITCH, LeakSeverity.WARN, "Kill switch is off; a drop can leak")
      }

  private fun directFinding(input: LeakInputs): LeakFinding =
      if (input.directPackageCount > 0) {
        LeakFinding(
            ID_DIRECT,
            LeakSeverity.WARN,
            "${input.directPackageCount} app(s) set to Direct (expected bypass)",
        )
      } else {
        LeakFinding(ID_DIRECT, LeakSeverity.OK, "No Direct apps")
      }

  private fun sightingFinding(input: LeakInputs): LeakFinding {
    val err = input.sightingError
    if (err != null) {
      return LeakFinding(ID_SIGHTING, LeakSeverity.WARN, "Public IP check failed: $err")
    }
    val ip = input.publicIp
    if (ip.isNullOrBlank()) {
      return LeakFinding(ID_SIGHTING, LeakSeverity.WARN, "No public IP fetched yet")
    }
    if (input.publicIpIsMesh) {
      return LeakFinding(ID_SIGHTING, LeakSeverity.WARN, "Endpoint returned mesh address $ip")
    }
    return if (input.vpnActive) {
      LeakFinding(
          ID_SIGHTING,
          LeakSeverity.OK,
          "This app's path sees $ip (not other apps; says nothing about DNS)",
          LeakEvidence.OBSERVED,
      )
    } else {
      LeakFinding(ID_SIGHTING, LeakSeverity.FAIL, "Public IP $ip while VPN is down", LeakEvidence.OBSERVED)
    }
  }

  private fun dnsFinding(input: LeakInputs): LeakFinding {
    val corp = input.corpDns
    return when {
      corp == null -> LeakFinding(ID_DNS, LeakSeverity.INCONCLUSIVE, "Mesh DNS setting unknown")
      // A setting is not a measurement: "on" only means apps are told to ask the mesh resolver.
      corp -> LeakFinding(ID_DNS, LeakSeverity.INCONCLUSIVE, "Mesh DNS is switched on; whether queries really go there is not measured here")
      else -> LeakFinding(ID_DNS, LeakSeverity.WARN, "Mesh DNS is off; system DNS may leak")
    }
  }
}
