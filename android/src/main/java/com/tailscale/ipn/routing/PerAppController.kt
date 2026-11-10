// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.routing

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tailscale.ipn.App
import com.tailscale.ipn.proxy.ProxyStore
import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.SavedProxyPlanner
import com.tailscale.ipn.proxy.SavedProxyStore
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.WireGuardManager
import libtailscale.Libtailscale

/**
 * Pushes the per-app exit/proxy table to libtailscale. Off by default:
 * with [PerAppStore.enforce] off the Go side gets no policy and behaves
 * exactly as before.
 */
object PerAppController {
  private const val TAG = "PerAppRouting"
  private val lock = Any()
  @Volatile private var lastPushed: String? = null

  private fun secrets() = EncryptedPrefsSecretStore(App.get().getEncryptedPrefs())

  fun store(): PerAppStore = PerAppStore(secrets())

  fun savedProxies(): SavedProxyStore = SavedProxyStore(secrets())

  /** Saved proxies plus the Settings → Proxy upstream (if filled in). */
  fun proxyChoices(): List<SavedProxy> =
      listOfNotNull(SavedProxyPlanner.fromMainUpstream(ProxyStore(secrets()).upstream())) +
          savedProxies().list()

  fun deviceExitNodeId(): String? = Notifier.prefs.value?.activeExitNodeID

  fun exitNodeLabel(id: String): String =
      Notifier.netmap.value?.Peers?.firstOrNull { it.StableID == id }?.let { it.ComputedName ?: it.Name }
          ?: "that exit node"

  fun resolve(
      route: AppRoute,
      perApp: PerAppStore = store(),
      proxies: List<SavedProxy> = proxyChoices(),
  ): ResolvedAppRoute =
      PerAppExitPlanner.resolve(
          route = route,
          enforce = perApp.enforce(),
          failClosed = perApp.failClosed(),
          deviceExitNodeId = deviceExitNodeId(),
          proxies = proxies,
          sdkInt = Build.VERSION.SDK_INT,
          exitNodeLabel = ::exitNodeLabel,
      )

  fun resolveAll(): List<ResolvedAppRoute> {
    val s = store()
    val proxies = proxyChoices()
    return s.routes().map { resolve(it, s, proxies) }
  }

  /** Apps that must stay inside the VPN (they override the split-tunnel list). */
  fun capturedPackages(): List<String> {
    val s = store()
    if (!s.enforce() || !PerAppExitPlanner.supported(Build.VERSION.SDK_INT)) return emptyList()
    if (WireGuardManager.isWireGuardActive()) return emptyList()
    return PerAppExitPlanner.capturedPackages(resolveAll())
  }

  fun sync(ctx: Context) {
    synchronized(lock) {
      try {
        val s = store()
        if (!s.enforce() || !PerAppExitPlanner.supported(Build.VERSION.SDK_INT) || WireGuardManager.isWireGuardActive()) {
          clearLocked()
          return
        }
        val pm = ctx.packageManager
        val rules = resolveAll().mapNotNull { r -> uidOf(pm, r.pkg)?.let { PerAppRule(it, r) } }
        val json =
            PerAppExitPlanner.policyJson(
                enabled = true,
                exitNodeActive = !deviceExitNodeId().isNullOrEmpty(),
                failClosed = s.failClosed(),
                rules = rules,
            )
        if (json == lastPushed) return
        Libtailscale.setPerAppPolicy(json)
        lastPushed = json
        TSLog.d(TAG, "per-app policy pushed: ${rules.size} app(s)")
      } catch (e: Exception) {
        TSLog.e(TAG, "per-app policy: ${e.javaClass.simpleName}")
      }
    }
  }

  fun clear() {
    synchronized(lock) { clearLocked() }
  }

  private fun clearLocked() {
    if (lastPushed == null) return
    try {
      Libtailscale.clearPerAppPolicy()
    } catch (e: Exception) {
      TSLog.e(TAG, "per-app clear: ${e.javaClass.simpleName}")
    }
    lastPushed = null
  }

  fun unknownOwners(): Long =
      try {
        Libtailscale.perAppUnknownOwners()
      } catch (_: Throwable) {
        0L
      }

  private fun uidOf(pm: PackageManager, pkg: String): Int? =
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).uid
        } else {
          @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0).uid
        }
      } catch (_: PackageManager.NameNotFoundException) {
        null
      }
}
