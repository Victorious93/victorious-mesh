// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

import com.tailscale.ipn.wireguard.SecretStore

enum class UpstreamKind {
  NONE,
  HTTP,
  SOCKS5,
}

data class MeshProxyConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_MESH_PORT,
    val username: String = "",
    val password: String = "",
) {
  override fun toString(): String =
      "MeshProxyConfig(enabled=$enabled, port=$port, username=${username.isNotEmpty()}, password=***)"
}

data class UpstreamProxyConfig(
    val kind: UpstreamKind = UpstreamKind.NONE,
    val host: String = "",
    val port: Int = DEFAULT_UPSTREAM_PORT,
    val username: String = "",
    val password: String = "",
) {
  override fun toString(): String =
      "UpstreamProxyConfig(kind=$kind, host=$host, port=$port, username=${username.isNotEmpty()}, password=***)"
}

const val DEFAULT_MESH_PORT = 1080
const val DEFAULT_UPSTREAM_PORT = 8080
const val MIN_PROXY_PORT = 1024
const val MAX_PROXY_PORT = 65535

class ProxyStore(private val store: SecretStore) {
  fun mesh(): MeshProxyConfig =
      MeshProxyConfig(
          enabled = store.get(KEY_MESH_ENABLED) == "1",
          port = store.get(KEY_MESH_PORT)?.toIntOrNull() ?: DEFAULT_MESH_PORT,
          username = store.get(KEY_MESH_USER).orEmpty(),
          password = store.get(KEY_MESH_PASS).orEmpty(),
      )

  fun saveMesh(cfg: MeshProxyConfig) {
    store.put(KEY_MESH_ENABLED, if (cfg.enabled) "1" else "0")
    store.put(KEY_MESH_PORT, cfg.port.toString())
    store.put(KEY_MESH_USER, cfg.username)
    store.put(KEY_MESH_PASS, cfg.password)
  }

  fun upstream(): UpstreamProxyConfig =
      UpstreamProxyConfig(
          kind = runCatching { UpstreamKind.valueOf(store.get(KEY_UP_KIND) ?: "NONE") }.getOrDefault(UpstreamKind.NONE),
          host = store.get(KEY_UP_HOST).orEmpty(),
          port = store.get(KEY_UP_PORT)?.toIntOrNull() ?: DEFAULT_UPSTREAM_PORT,
          username = store.get(KEY_UP_USER).orEmpty(),
          password = store.get(KEY_UP_PASS).orEmpty(),
      )

  fun saveUpstream(cfg: UpstreamProxyConfig) {
    store.put(KEY_UP_KIND, cfg.kind.name)
    store.put(KEY_UP_HOST, cfg.host.trim())
    store.put(KEY_UP_PORT, cfg.port.toString())
    store.put(KEY_UP_USER, cfg.username)
    store.put(KEY_UP_PASS, cfg.password)
  }

  companion object {
    private const val KEY_MESH_ENABLED = "proxy.mesh.enabled"
    private const val KEY_MESH_PORT = "proxy.mesh.port"
    private const val KEY_MESH_USER = "proxy.mesh.user"
    private const val KEY_MESH_PASS = "proxy.mesh.pass"
    private const val KEY_UP_KIND = "proxy.up.kind"
    private const val KEY_UP_HOST = "proxy.up.host"
    private const val KEY_UP_PORT = "proxy.up.port"
    private const val KEY_UP_USER = "proxy.up.user"
    private const val KEY_UP_PASS = "proxy.up.pass"
  }
}

object ProxyPlanner {
  fun validPort(port: Int): Boolean = port in MIN_PROXY_PORT..MAX_PROXY_PORT

  /** Both empty (open proxy) or both set. One-sided credentials are rejected. */
  fun authComplete(username: String, password: String): Boolean =
      (username.isEmpty() && password.isEmpty()) || (username.isNotEmpty() && password.isNotEmpty())

  /**
   * Mesh bind: must be this node's own mesh address. Never unspecified, loopback,
   * RFC1918 LAN, or a public IP.
   */
  fun allowedMeshBind(ip: String, selfAddresses: List<String>): Boolean {
    if (ip.isBlank()) return false
    val host = ip.substringBefore('/')
    if (host == "0.0.0.0" || host == "::" || host == "127.0.0.1" || host == "::1") return false
    val selfHosts = selfAddresses.map { it.substringBefore('/') }
    return host in selfHosts && looksLikeMesh(host)
  }

  fun firstMeshIPv4(selfAddresses: List<String>): String? =
      selfAddresses.map { it.substringBefore('/') }.firstOrNull { looksLikeMeshV4(it) }

  fun looksLikeMesh(ip: String): Boolean = looksLikeMeshV4(ip) || looksLikeMeshV6(ip)

  fun looksLikeMeshV4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 100 && b in 64..127
  }

  fun looksLikeMeshV6(ip: String): Boolean {
    val n = ip.lowercase()
    return n.startsWith("fd7a:115c:a1e0:") || n == "fd7a:115c:a1e0::" || n.startsWith("fd7a:115c:a1e0::")
  }

  fun planOutbound(kind: UpstreamKind, host: String, port: Int, sdkInt: Int): OutboundPlan {
    if (kind == UpstreamKind.NONE || host.isBlank() || !validPort(port)) {
      return OutboundPlan(applyHttpProxy = false, excludeHost = null, reason = "disabled")
    }
    if (kind == UpstreamKind.SOCKS5) {
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = null,
          reason = "SOCKS5 cannot be applied to all apps; VpnService has no SOCKS API",
      )
    }
    if (sdkInt < 29) {
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = null,
          reason = "setHttpProxy requires Android 10",
      )
    }
    // Exclude only non-mesh literal IPs so we can reach an internet proxy
    // without a VPN routing loop. A mesh-IP upstream must stay inside the VPN.
    val exclude = host.takeIf { it.looksLikeLiteralIp() && !looksLikeMesh(it) }
    return OutboundPlan(
        applyHttpProxy = true,
        excludeHost = exclude,
        reason = "advisory HTTP proxy via VpnService; apps may ignore it",
    )
  }

  private fun String.looksLikeLiteralIp(): Boolean =
      matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || contains(':')
}

data class OutboundPlan(
    val applyHttpProxy: Boolean,
    val excludeHost: String?,
    val reason: String,
)
