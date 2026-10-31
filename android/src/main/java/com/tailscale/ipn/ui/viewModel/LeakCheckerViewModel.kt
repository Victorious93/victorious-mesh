// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.killswitch.KillSwitchStore
import com.tailscale.ipn.leakcheck.LeakCheckerPlanner
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

  fun runCheck() {
    if (checking.value) return
    checking.set(true)
    message.set(null)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        findings.set(check())
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
            )
        )
    return report
  }

  private class InsecureEndpoint : Exception()
}
