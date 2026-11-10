// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.dns

import com.tailscale.ipn.wireguard.SecretStore
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which networks a rule applies to. */
enum class DnsMatch {
  /** A Wi-Fi network with this exact name (needs location permission to read the name). */
  WIFI_SSID,
  /** Any Wi-Fi network without its own rule. */
  ANY_WIFI,
  /** Mobile data. */
  CELLULAR,
}

@Serializable
data class DnsRule(
    val id: String,
    val match: DnsMatch,
    val ssid: String = "",
    val servers: List<String> = emptyList(),
)

@Serializable
data class PerNetworkDnsConfig(val enabled: Boolean = false, val rules: List<DnsRule> = emptyList())

/** What the phone's underlying (non-VPN) network is right now. */
data class CurrentNetwork(val wifi: Boolean, val cellular: Boolean, val ssid: String?)

class PerNetworkDnsStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun load(): PerNetworkDnsConfig =
      store.get(KEY)?.let { raw ->
        runCatching { json.decodeFromString(PerNetworkDnsConfig.serializer(), raw) }.getOrNull()
      } ?: PerNetworkDnsConfig()

  fun save(c: PerNetworkDnsConfig) = store.put(KEY, json.encodeToString(PerNetworkDnsConfig.serializer(), c))

  companion object {
    const val KEY = "pndns.config"
  }
}

object PerNetworkDnsPlanner {
  const val MAX_SERVERS = 4
  const val MAX_RULES = 20
  private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
  private val V6 = Regex("^[0-9A-Fa-f:.]+$")

  /**
   * Parse a DNS server literal. Only IP addresses (never host names, so nothing
   * is looked up). Refuses the mesh's own resolver (it would loop), loopback,
   * any-address, multicast and broadcast.
   */
  fun parseServer(raw: String): String? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    val addr: InetAddress =
        when {
          V4.matches(s) -> {
            val parts = V4.find(s)!!.groupValues.drop(1)
            if (parts.any { it.length > 1 && it.startsWith("0") || it.toInt() > 255 }) return null
            InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
          }
          s.contains(':') && V6.matches(s) -> runCatching { InetAddress.getByName(s) }.getOrNull() ?: return null
          else -> return null
        }
    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) return null
    if (addr is Inet4Address) {
      val h = addr.hostAddress
      if (h == "100.100.100.100" || h == "255.255.255.255") return null
      return h
    }
    if (addr is Inet6Address) {
      if (addr.isLinkLocalAddress) return null
      val h = addr.hostAddress!!.substringBefore('%')
      if (h.equals("fd7a:115c:a1e0:0:0:0:0:53", ignoreCase = true)) return null // mesh resolver over IPv6
      return h
    }
    return null
  }

  /** Split "1.1.1.1, 9.9.9.9" (commas or spaces) and validate. Null if any entry is bad. */
  fun parseServers(raw: String): List<String>? {
    val items = raw.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (items.isEmpty() || items.size > MAX_SERVERS) return null
    val out = items.map { parseServer(it) ?: return null }
    return out.distinct()
  }

  /** Android reports SSIDs in quotes, or "<unknown ssid>" without location permission. */
  fun cleanSsid(raw: String?): String? {
    val s = raw?.trim()?.removeSurrounding("\"") ?: return null
    if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
    return s
  }

  fun problem(r: DnsRule): String? {
    if (r.match == DnsMatch.WIFI_SSID && cleanSsid(r.ssid) == null) return "type the Wi-Fi name"
    if (r.match == DnsMatch.WIFI_SSID && r.ssid.length > 32) return "Wi-Fi names are at most 32 characters"
    if (r.servers.isEmpty() || r.servers.size > MAX_SERVERS) return "add 1 to $MAX_SERVERS DNS server IP addresses"
    if (r.servers.any { parseServer(it) == null }) return "DNS servers must be IP addresses (not 100.100.100.100 or loopback)"
    return null
  }

  /**
   * A named Wi-Fi rule wins, then "any Wi-Fi", then "mobile data". Null = use
   * the network's own DNS. Paused while the forced proxy is on, so forced-proxy
   * DNS behaves exactly as it does without this feature.
   */
  fun pick(c: PerNetworkDnsConfig, net: CurrentNetwork, forcedProxy: Boolean = false): DnsRule? {
    if (!c.enabled || forcedProxy) return null
    val valid = c.rules.filter { problem(it) == null }
    if (net.wifi) {
      val ssid = cleanSsid(net.ssid)
      if (ssid != null) valid.firstOrNull { it.match == DnsMatch.WIFI_SSID && cleanSsid(it.ssid) == ssid }?.let { return it }
      return valid.firstOrNull { it.match == DnsMatch.ANY_WIFI }
    }
    if (net.cellular) return valid.firstOrNull { it.match == DnsMatch.CELLULAR }
    return null
  }

  fun needsSsid(c: PerNetworkDnsConfig): Boolean = c.enabled && c.rules.any { it.match == DnsMatch.WIFI_SSID }

  /**
   * The base DNS string handed to the mesh core is "servers\nsearch domains".
   * Replace only the servers line; keep the network's search domains.
   */
  fun applyToBase(base: String, rule: DnsRule?): String {
    if (rule == null) return base
    val lines = base.split('\n')
    val domains = if (lines.size > 1) lines.drop(1).joinToString("\n") else null
    val servers = rule.servers.joinToString(" ") + " "
    return if (domains != null) "$servers\n$domains" else servers
  }

  fun upsert(c: PerNetworkDnsConfig, r: DnsRule): PerNetworkDnsConfig {
    val withId = if (r.id.isBlank()) r.copy(id = UUID.randomUUID().toString()) else r
    val clean =
        withId.copy(
            ssid = if (withId.match == DnsMatch.WIFI_SSID) cleanSsid(withId.ssid) ?: "" else "",
            servers = withId.servers.mapNotNull { parseServer(it) }.distinct())
    // One rule per network: a new rule for the same network replaces the old one.
    val others =
        c.rules.filterNot {
          it.id == clean.id ||
              (it.match == clean.match && (clean.match != DnsMatch.WIFI_SSID || it.ssid == clean.ssid))
        }
    return c.copy(rules = (others + clean).take(MAX_RULES))
  }
}
