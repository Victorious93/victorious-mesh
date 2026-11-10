// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.routing.AppRoute
import com.tailscale.ipn.routing.AppRouteKind
import com.tailscale.ipn.routing.IpSightingParser
import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.SavedProxyPlanner
import com.tailscale.ipn.routing.PerAppController
import com.tailscale.ipn.routing.PerAppExitPlanner
import com.tailscale.ipn.routing.PerAppPlanner
import com.tailscale.ipn.routing.PerAppStore
import com.tailscale.ipn.routing.ResolvedAppRoute
import com.tailscale.ipn.routing.PublicSighting
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import java.net.HttpURLConnection
import android.os.Build
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExitChoice(val id: String, val label: String)

class PerAppRoutingViewModel(app: Application) : AndroidViewModel(app) {
  private val store = PerAppStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  val installedApps: StateFlow<List<InstalledApp>> =
      flow { emit(InstalledAppsManager(App.get().packageManager).fetchInstalledApps()) }
          .flowOn(Dispatchers.IO)
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val routes: StateFlow<List<AppRoute>> = MutableStateFlow(store.routes())
  val endpoint: StateFlow<String> = MutableStateFlow(store.sightingEndpoint())
  val sighting: StateFlow<PublicSighting?> = MutableStateFlow(null)
  val message: StateFlow<String?> = MutableStateFlow(null)
  val checking: StateFlow<Boolean> = MutableStateFlow(false)
  val enforce: StateFlow<Boolean> = MutableStateFlow(store.enforce())
  val failClosed: StateFlow<Boolean> = MutableStateFlow(store.failClosed())
  val savedProxies: StateFlow<List<SavedProxy>> = MutableStateFlow(PerAppController.savedProxies().list())
  val proxyMessage: StateFlow<String?> = MutableStateFlow(null)
  val unknownOwners: StateFlow<Long> = MutableStateFlow(PerAppController.unknownOwners())
  val supported: Boolean = PerAppExitPlanner.supported(Build.VERSION.SDK_INT)

  /** Saved proxies plus the Settings → Proxy upstream, for the per-app picker. */
  fun proxyChoices(): List<SavedProxy> = PerAppController.proxyChoices()

  fun resolved(pkg: String): ResolvedAppRoute = PerAppController.resolve(routeFor(pkg), store)

  /** Which apps sit outside / are forced inside the tunnel; a change needs a VPN restart. */
  private fun membershipKey(): String =
      PerAppPlanner.directPackages(store.routes()).sorted().joinToString(",") +
          "|" + PerAppController.capturedPackages().sorted().joinToString(",")

  private inline fun change(block: () -> Unit) {
    val before = membershipKey()
    block()
    routes.set(store.routes())
    enforce.set(store.enforce())
    failClosed.set(store.failClosed())
    savedProxies.set(PerAppController.savedProxies().list())
    PerAppController.sync(App.get())
    unknownOwners.set(PerAppController.unknownOwners())
    if (membershipKey() != before) App.get().restartVPN()
  }

  fun setEnforce(on: Boolean) = change { store.setEnforce(on) }

  fun setFailClosed(on: Boolean) = change { store.setFailClosed(on) }

  fun setProxy(pkg: String, proxyId: String) = change {
    store.setRoute(AppRoute(pkg, AppRouteKind.UPSTREAM_PROXY, proxyId = proxyId))
  }

  /** Returns null when saved, otherwise the problem to show. */
  fun saveProxy(p: SavedProxy): String? {
    val problem = SavedProxyPlanner.problem(p) ?: if (p.name.isBlank()) "name is empty" else null
    if (problem != null) {
      proxyMessage.set("Not saved: $problem")
      return problem
    }
    change { PerAppController.savedProxies().save(p) }
    proxyMessage.set("Saved ${p.name.trim()}")
    return null
  }

  fun deleteProxy(id: String) = change { PerAppController.savedProxies().delete(id) }

  fun exitChoices(): List<ExitChoice> {
    val peers = Notifier.netmap.value?.Peers.orEmpty().filter { it.isExitNode }
    return peers.map { ExitChoice(it.StableID, it.ComputedName ?: it.Name) }
  }

  fun routeFor(pkg: String): AppRoute = routes.value.firstOrNull { it.pkg == pkg } ?: AppRoute(pkg)

  fun appliedReason(pkg: String): String = PerAppPlanner.applied(routeFor(pkg)).reason

  fun setKind(pkg: String, kind: AppRouteKind) = change {
    val cur = routeFor(pkg)
    store.setRoute(cur.copy(kind = kind))
  }

  fun setExitNode(pkg: String, exitNodeId: String) = change {
    store.setRoute(routeFor(pkg).copy(kind = AppRouteKind.EXIT_NODE, exitNodeId = exitNodeId))
  }

  fun setEndpoint(url: String) {
    store.setSightingEndpoint(url)
    endpoint.set(store.sightingEndpoint())
  }

  fun checkSighting() {
    if (checking.value) return
    checking.set(true)
    message.set(null)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val conn = URL(store.sightingEndpoint()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val seen = IpSightingParser.parse(body)
        sighting.set(seen)
        message.set(
            if (seen.ip != null && IpSightingParser.looksLikeMeshIPv4(seen.ip))
                "Endpoint returned a mesh address, not a public IP"
            else null
        )
      } catch (e: Exception) {
        message.set("Check failed: ${e.javaClass.simpleName}")
      } finally {
        checking.set(false)
      }
    }
  }
}
