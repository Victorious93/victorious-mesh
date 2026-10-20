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
}

data class LeakFinding(
    val id: String,
    val severity: LeakSeverity,
    val detail: String,
)

data class LeakInputs(
    val vpnActive: Boolean,
    val killSwitchOn: Boolean,
    val directPackageCount: Int,
    val publicIp: String?,
    val publicIpIsMesh: Boolean,
    val sightingError: String?,
    val corpDns: Boolean?,
)

object LeakCheckerPlanner {
  const val ID_VPN = "vpn"
  const val ID_KILLSWITCH = "killswitch"
  const val ID_DIRECT = "direct"
  const val ID_SIGHTING = "sighting"
  const val ID_DNS = "dns"
  const val ID_SCOPE = "scope"

  fun report(input: LeakInputs): List<LeakFinding> {
    return listOf(
        vpnFinding(input),
        killSwitchFinding(input),
        directFinding(input),
        sightingFinding(input),
        dnsFinding(input),
        LeakFinding(
            ID_SCOPE,
            LeakSeverity.WARN,
            "Cannot test other apps, WebRTC/STUN, or sockets opened before the VPN",
        ),
    )
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
      LeakFinding(ID_SIGHTING, LeakSeverity.OK, "This app's path sees $ip (not other apps)")
    } else {
      LeakFinding(ID_SIGHTING, LeakSeverity.FAIL, "Public IP $ip while VPN is down")
    }
  }

  private fun dnsFinding(input: LeakInputs): LeakFinding {
    val corp = input.corpDns
    return when {
      corp == null -> LeakFinding(ID_DNS, LeakSeverity.WARN, "Mesh DNS state unknown")
      corp -> LeakFinding(ID_DNS, LeakSeverity.OK, "Mesh DNS is on")
      else -> LeakFinding(ID_DNS, LeakSeverity.WARN, "Mesh DNS is off; system DNS may leak")
    }
  }
}
