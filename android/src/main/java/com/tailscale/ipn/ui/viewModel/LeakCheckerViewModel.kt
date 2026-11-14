// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.killswitch.KillSwitchStore
import com.tailscale.ipn.dns.DnsToolsPlanner
import com.tailscale.ipn.dns.PerNetworkDns
import com.tailscale.ipn.leakcheck.LeakCheckerPlanner
import com.tailscale.ipn.leakcheck.LeakEvidence
import com.tailscale.ipn.leakcheck.LeakSeverity
import com.tailscale.ipn.proxy.MeshProxyController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import libtailscale.Libtailscale
import com.tailscale.ipn.leakcheck.LeakFinding
import com.tailscale.ipn.leakcheck.LeakInputs
import com.tailscale.ipn.routing.IpSightingParser
import com.tailscale.ipn.routing.PerAppPlanner
import com.tailscale.ipn.routing.PerAppStore
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LeakCheckerViewModel(app: Application) : AndroidViewModel(app) {
  val findings: StateFlow<List<LeakFinding>> = MutableStateFlow(emptyList())
  val checking: StateFlow<Boolean> = MutableStateFlow(false)
  val message: StateFlow<String?> = MutableStateFlow(null)
  val headline: StateFlow<Pair<LeakSeverity, String>?> = MutableStateFlow(null)
  val observer: StateFlow<LeakObserver> = MutableStateFlow(loadObserver())

  private fun secrets() = EncryptedPrefsSecretStore(App.get().getEncryptedPrefs())

  private fun loadObserver(): LeakObserver =
      secrets().let {
        LeakObserver(it.get("leak.obs.zone").orEmpty(), it.get("leak.obs.endpoint").orEmpty(), it.get("leak.obs.expected").orEmpty())
      }

  fun saveObserver(zone: String, endpoint: String, expected: String) {
    val s = secrets()
    s.put("leak.obs.zone", zone.trim())
    s.put("leak.obs.endpoint", endpoint.trim())
    s.put("leak.obs.expected", expected.trim())
    observer.set(loadObserver())
  }

  fun runCheck() {
    if (checking.value) return
    checking.set(true)
    message.set(null)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val list = check()
        findings.set(list)
        headline.set(LeakCheckerPlanner.overall(list))
      } catch (e: Exception) {
        message.set(e.javaClass.simpleName)
      } finally {
        // Never leave the button stuck on "checking".
        checking.set(false)
      }
    }
  }

  private fun check(): List<LeakFinding> {
    val secrets = EncryptedPrefsSecretStore(App.get().getEncryptedPrefs())
    val vpnActive = App.get().getAppScopedViewModel().vpnActive.value
    val killSwitchOn = KillSwitchStore(secrets).enabled()
    val direct = PerAppPlanner.directPackages(PerAppStore(secrets).routes()).size
    val corpDns = Notifier.prefs.value?.CorpDNS
    var ip: String? = null
    var mesh = false
    var err: String? = null
    try {
      val endpoint = PerAppStore(secrets).sightingEndpoint()
      if (!LeakCheckerPlanner.endpointAllowed(endpoint)) {
        throw InsecureEndpoint()
      }
      val conn = URL(endpoint).openConnection() as HttpURLConnection
      try {
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val seen = IpSightingParser.parse(body)
        ip = seen.ip
        mesh = seen.ip != null && IpSightingParser.looksLikeMeshIPv4(seen.ip)
      } finally {
        conn.disconnect()
      }
    } catch (_: InsecureEndpoint) {
      err = "endpoint must use https"
    } catch (e: Exception) {
      err = e.javaClass.simpleName
    }
    val report: List<LeakFinding> =
        LeakCheckerPlanner.report(
            LeakInputs(
                vpnActive = vpnActive,
                killSwitchOn = killSwitchOn,
                directPackageCount = direct,
                publicIp = ip,
                publicIpIsMesh = mesh,
                sightingError = err,
                corpDns = corpDns,
                forcedProxyFailOpen = secretsUpstream()?.let { it.forced && !it.failClosed } ?: false,
                perNetworkDnsIgnored = PerNetworkDns.effective.effect == com.tailscale.ipn.dns.DnsEffect.PAUSED_FORCED_PROXY,
                overlayDown = overlayDown(),
            )
        )
    return report + observeDns(corpDns == true, vpnActive, killSwitchOn)
  }

  /**
   * The only measured DNS finding: look up a unique name under the user's observation
   * zone and ask the service who asked. Without a service it is inconclusive.
   */
  private fun observeDns(meshDns: Boolean, vpnActive: Boolean, killSwitchOn: Boolean): LeakFinding {
    val o = observer.value
    val profile = if (meshDns) DnsToolsPlanner.presets[1] else DnsToolsPlanner.presets[0]
    val req =
        buildJsonObject {
              put("config", buildJsonObject { put("vpnActive", vpnActive); put("killSwitch", killSwitchOn); put("dnsMode", if (meshDns) "mesh" else "system") })
              if (o.zone.isNotBlank() && o.endpoint.isNotBlank()) {
                put(
                    "observer",
                    buildJsonObject {
                      put("zone", o.zone)
                      put("endpoint", o.endpoint)
                      putJsonArray("expected") { o.expectedList().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    })
              }
              put("profile", Json.parseToJsonElement(DnsToolsPlanner.profileJson(profile)))
            }
            .toString()
    runCatching { Libtailscale.setSystemDNSServers(MeshProxyController.currentNetworkDnsServers()) }
    val raw = runCatching { Libtailscale.leakCheck(req) }.getOrElse { return LeakFinding(LeakCheckerPlanner.ID_DNS_OBSERVED, LeakSeverity.INCONCLUSIVE, "DNS test could not run: ${it.message}") }
    val obj = Json.parseToJsonElement(raw).jsonObject
    obj["error"]?.let { return LeakFinding(LeakCheckerPlanner.ID_DNS_OBSERVED, LeakSeverity.INCONCLUSIVE, "DNS test could not run: ${it.jsonPrimitive.content}") }
    val f = obj["findings"]!!.jsonArray.map { it.jsonObject }.first { it["id"]?.jsonPrimitive?.content == "dns-observed" }
    return LeakFinding(
        LeakCheckerPlanner.ID_DNS_OBSERVED,
        when (f["verdict"]?.jsonPrimitive?.content) {
          "pass" -> LeakSeverity.OK
          "fail" -> LeakSeverity.FAIL
          "warn" -> LeakSeverity.WARN
          else -> LeakSeverity.INCONCLUSIVE
        },
        f["detail"]?.jsonPrimitive?.content.orEmpty(),
        when (f["evidence"]?.jsonPrimitive?.content) {
          "verified" -> LeakEvidence.VERIFIED
          "observed" -> LeakEvidence.OBSERVED
          else -> LeakEvidence.CONFIGURATION
        },
    )
  }

  private fun secretsUpstream() = runCatching { MeshProxyController.store(App.get()).upstream() }.getOrNull()

  /** Names the required Tor/I2P backend if the last connection test or forced status says it is down. */
  private fun overlayDown(): String? {
    val up = secretsUpstream() ?: return null
    if (!up.forced) return null
    val name =
        when (up.backend) {
          com.tailscale.ipn.proxy.OverlayBackend.TOR -> "Tor"
          com.tailscale.ipn.proxy.OverlayBackend.NONE -> return null
          else -> "I2P"
        }
    return if (MeshProxyController.forcedDNSState() == com.tailscale.ipn.proxy.ForcedDNSStatus.BLOCKED) name else null
  }

  private class InsecureEndpoint : Exception()
}

/** The user's DNS observation service (see the manual). */
data class LeakObserver(val zone: String, val endpoint: String, val expected: String) {
  fun expectedList(): List<String> = expected.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
