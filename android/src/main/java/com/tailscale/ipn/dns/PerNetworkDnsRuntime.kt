// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.dns

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import libtailscale.Libtailscale

/** Holds the per-network DNS choice in memory for the network callback. */
object PerNetworkDns {
  @Volatile private var cached: PerNetworkDnsConfig? = null

  @Volatile
  var lastApplied: DnsRule? = null
    private set

  @Volatile
  var lastNetwork: CurrentNetwork? = null
    private set

  /** True when the last check skipped the rules because the forced proxy is on. */
  @Volatile
  var pausedForForcedProxy: Boolean = false
    private set

  private fun store() = PerNetworkDnsStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  fun config(): PerNetworkDnsConfig =
      cached ?: (runCatching { store().load() }.getOrDefault(PerNetworkDnsConfig())).also { cached = it }

  fun save(c: PerNetworkDnsConfig) {
    store().save(c)
    cached = c
  }

  fun hasLocationPermission(ctx: Context): Boolean =
      ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

  /** Wi-Fi name, only when a named rule exists and location permission is granted. */
  @Suppress("DEPRECATION")
  fun currentSsid(ctx: Context): String? {
    if (!PerNetworkDnsPlanner.needsSsid(config()) || !hasLocationPermission(ctx)) return null
    return runCatching {
          val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
          PerNetworkDnsPlanner.cleanSsid(wm.connectionInfo?.ssid)
        }
        .getOrNull()
  }

  /** Called by the network callback with the network's own DNS; returns what the core should use. */
  fun resolveBase(base: String, wifi: Boolean, cellular: Boolean): String {
    val c = config()
    if (!c.enabled) {
      lastApplied = null
      pausedForForcedProxy = false
      lastNetwork = CurrentNetwork(wifi, cellular, null)
      return base
    }
    // If the forced-proxy state cannot be read, assume it is on (pause).
    val forced = runCatching { Libtailscale.forcedUpstreamEnabled() }.getOrDefault(true)
    val ssid = if (wifi) runCatching { currentSsid(App.get()) }.getOrNull() else null
    val net = CurrentNetwork(wifi, cellular, ssid)
    val rule = PerNetworkDnsPlanner.pick(c, net, forcedProxy = forced)
    pausedForForcedProxy = forced
    lastNetwork = net
    lastApplied = rule
    return PerNetworkDnsPlanner.applyToBase(base, rule)
  }
}
