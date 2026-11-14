// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.dns

import com.tailscale.ipn.wireguard.SecretStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a resolver profile talks to its server. Wire names match the Go core. */
enum class DnsTransport(val wire: String) {
  SYSTEM("system"),
  UDP("udp"),
  TCP("tcp"),
  DOT("dot"),
  DOH("doh"),
}

/** The path a resolver profile's queries take. Never changes by itself. */
enum class DnsRoute(val wire: String) {
  /** Protected socket on Wi-Fi/cellular, outside the VPN. */
  DIRECT("direct"),
  /** Through the mesh tunnel (MagicDNS / split DNS see the query). */
  MESH("mesh"),
  /** Through the forced upstream proxy. Fails if no proxy is armed. */
  PROXY("proxy"),
  /** Through the Tor backend. Fails if Tor is not the armed backend. */
  TOR("tor"),
}

@Serializable
data class ResolverProfile(
    val id: String,
    val name: String,
    val transport: String = DnsTransport.UDP.wire,
    /** host[:port] for udp/tcp/dot; an https:// URL for doh. Ignored for "system". */
    val server: String = "",
    /** TLS name to verify for dot/doh; empty = the server host. */
    val serverName: String = "",
    /** Literal IPs used to reach a hostname server. Hostnames are never looked up with other DNS. */
    val bootstrap: List<String> = emptyList(),
    val route: String = DnsRoute.DIRECT.wire,
    /** May see local / private-zone / private-reverse names (mesh DNS, the network's own DNS). */
    val private: Boolean = false,
)

object DnsToolsPlanner {
  const val MAX_PROFILES = 30
  private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
  private val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

  fun isIpLiteral(s: String): Boolean {
    val t = s.trim().trim('[', ']')
    val m = V4.matchEntire(t)
    if (m != null) return m.groupValues.drop(1).all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    return t.contains(':') && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } && t.count { it == ':' } in 2..7
  }

  /** The built-in profiles: always present, not editable. */
  val presets: List<ResolverProfile> =
      listOf(
          ResolverProfile("preset-system", "Network DNS (this network's servers)", DnsTransport.SYSTEM.wire, route = DnsRoute.DIRECT.wire, private = true),
          ResolverProfile("preset-mesh", "Mesh / MagicDNS + split DNS", DnsTransport.UDP.wire, "100.100.100.100:53", route = DnsRoute.MESH.wire, private = true),
      )

  /** Null when usable; otherwise a plain-language reason. */
  fun problem(p: ResolverProfile): String? {
    val transport = DnsTransport.entries.firstOrNull { it.wire == p.transport } ?: return "pick a transport"
    val route = DnsRoute.entries.firstOrNull { it.wire == p.route } ?: return "pick a route"
    if (p.name.isBlank()) return "give the profile a name"
    if (transport != DnsTransport.SYSTEM) {
      val server = p.server.trim()
      if (server.isEmpty()) return "enter a server"
      if (transport == DnsTransport.DOH) {
        if (!server.startsWith("https://", ignoreCase = true)) return "DNS over HTTPS needs an https:// URL (plain http is never used)"
        val host = server.removePrefix("https://").removePrefix("HTTPS://").substringBefore('/').substringBeforeLast(':', server.removePrefix("https://").substringBefore('/'))
        if (host.isBlank()) return "the URL has no host"
        if (!isIpLiteral(host) && p.bootstrap.isEmpty() && route != DnsRoute.PROXY && route != DnsRoute.TOR) {
          return "a hostname needs bootstrap IP addresses (it is never looked up with other DNS)"
        }
      } else {
        val host = hostOf(server)
        if (host.isBlank() || !(isIpLiteral(host) || HOSTNAME.matches(host))) return "the server must be an IP address or a host name"
        if (!isIpLiteral(host) && p.bootstrap.isEmpty() && route != DnsRoute.PROXY && route != DnsRoute.TOR) {
          return "a hostname needs bootstrap IP addresses (it is never looked up with other DNS)"
        }
        if (!isIpLiteral(host) && (transport == DnsTransport.UDP || transport == DnsTransport.TCP) && p.bootstrap.isEmpty()) {
          return "plain DNS servers must be IP addresses"
        }
      }
    }
    if (p.bootstrap.any { !isIpLiteral(it) }) return "bootstrap entries must be IP addresses"
    if (p.serverName.isNotBlank() && transport != DnsTransport.DOT && transport != DnsTransport.DOH) return "a TLS name only applies to DoT and DoH"
    if ((route == DnsRoute.PROXY || route == DnsRoute.TOR) && (transport == DnsTransport.UDP || transport == DnsTransport.SYSTEM)) {
      return "UDP cannot go through a proxy or Tor: use TCP, DoT or DoH"
    }
    return null
  }

  private fun hostOf(server: String): String {
    val s = server.trim()
    return when {
      s.startsWith("[") -> s.substringAfter('[').substringBefore(']')
      s.count { it == ':' } == 1 -> s.substringBefore(':')
      else -> s
    }
  }

  fun profileJson(p: ResolverProfile): String =
      buildJsonObject {
            put("id", p.id)
            put("name", p.name)
            put("transport", p.transport)
            put("server", p.server.trim())
            if (p.serverName.isNotBlank()) put("serverName", p.serverName.trim())
            if (p.bootstrap.isNotEmpty()) put("bootstrap", JsonArray(p.bootstrap.map { JsonPrimitive(it.trim()) }))
            put("route", p.route)
            if (p.private) put("private", true)
          }
          .toString()

  fun profilesJson(ps: List<ResolverProfile>): String = JsonArray(ps.map { Json.parseToJsonElement(profileJson(it)) }).toString()

  /** One-line summary of what the profile does, shown under its name. */
  fun describe(p: ResolverProfile): String {
    val t = DnsTransport.entries.firstOrNull { it.wire == p.transport }
    val enc = if (t == DnsTransport.DOT || t == DnsTransport.DOH) "encrypted, certificate checked" else "not encrypted"
    return "${t?.wire?.uppercase() ?: "?"} · $enc · route ${p.route}"
  }

  /** Parses "name ip [ip…]" lines into the local-policy JSON the core takes. */
  fun localPolicyJson(hostsText: String, allowText: String, blockText: String): Result<String> {
    val hosts = linkedMapOf<String, List<String>>()
    for (raw in hostsText.lines()) {
      val line = raw.substringBefore('#').trim()
      if (line.isEmpty()) continue
      val parts = line.split(Regex("\\s+"))
      if (parts.size < 2) return Result.failure(IllegalArgumentException("host line needs a name and an IP: \"$line\""))
      val ips = parts.drop(1)
      if (ips.any { !isIpLiteral(it) }) return Result.failure(IllegalArgumentException("not an IP address in: \"$line\""))
      hosts[parts[0].lowercase().trimEnd('.')] = ips
    }
    fun rules(t: String) = t.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    return Result.success(
        buildJsonObject {
              put("hosts", buildJsonObject { hosts.forEach { (k, v) -> put(k, JsonArray(v.map { JsonPrimitive(it) })) } })
              put("allow", JsonArray(rules(allowText).map { JsonPrimitive(it) }))
              put("block", JsonArray(rules(blockText).map { JsonPrimitive(it) }))
            }
            .toString())
  }
}

/** User-created resolver profiles, in the same encrypted store as other settings. */
class ResolverProfileStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun list(): List<ResolverProfile> {
    val raw = store.get(KEY) ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(ResolverProfile.serializer()), raw) }.getOrDefault(emptyList())
  }

  fun all(): List<ResolverProfile> = DnsToolsPlanner.presets + list()

  fun save(p: ResolverProfile): ResolverProfile {
    val withId = if (p.id.isBlank() || p.id.startsWith("preset-")) p.copy(id = UUID.randomUUID().toString()) else p
    val clean = withId.copy(name = withId.name.trim(), server = withId.server.trim())
    write((list().filterNot { it.id == clean.id } + clean).take(DnsToolsPlanner.MAX_PROFILES))
    return clean
  }

  fun duplicate(id: String): ResolverProfile? {
    val src = all().firstOrNull { it.id == id } ?: return null
    return save(src.copy(id = "", name = "${src.name} (copy)"))
  }

  fun delete(id: String) = write(list().filterNot { it.id == id })

  private fun write(l: List<ResolverProfile>) = store.put(KEY, json.encodeToString(ListSerializer(ResolverProfile.serializer()), l))

  companion object {
    const val KEY = "dnstools.profiles"
  }
}
