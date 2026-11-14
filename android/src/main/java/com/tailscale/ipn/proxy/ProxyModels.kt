// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

import com.tailscale.ipn.wireguard.SecretStore

enum class UpstreamKind {
  NONE,
  /** HTTP CONNECT over plaintext TCP to the proxy. */
  HTTP,
  SOCKS5,
  /** HTTP CONNECT inside a certificate-verified TLS session to the proxy. */
  HTTPS,
  /** I2P SAM v3 bridge (streams only). */
  SAM;

  /** The kind string the Go core expects. */
  val wire: String
    get() =
        when (this) {
          NONE -> ""
          HTTP -> "http"
          HTTPS -> "https"
          SOCKS5 -> "socks5"
          SAM -> "sam"
        }
}

/** Naming-aware service behind a proxy endpoint. */
enum class OverlayBackend(val wire: String) {
  NONE(""),
  /** A Tor SOCKS5 port (Orbot in SOCKS-only mode, tor on loopback, or a mesh-hosted gateway). */
  TOR("tor"),
  /** An I2P router's SAM bridge: .i2p names, streams only. */
  I2P_SAM("i2p-sam"),
  /** An I2P router's HTTP proxy: .i2p via HTTP; clearnet only through its own outproxy. */
  I2P_HTTP("i2p-http"),
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
    val forced: Boolean = false,
    val failClosed: Boolean = true,
    /** TLS name checked against an https proxy's certificate; empty = the host. */
    val serverName: String = "",
    val backend: OverlayBackend = OverlayBackend.NONE,
    /** Optional, explicit clearnet exit for I2P backends. Empty = clearnet refused. */
    val outproxy: String = "",
) {
  override fun toString(): String =
      "UpstreamProxyConfig(kind=$kind, host=$host, port=$port, username=${username.isNotEmpty()}, password=***, forced=$forced, failClosed=$failClosed, backend=$backend)"
}

const val DEFAULT_MESH_PORT = 1080
const val DEFAULT_UPSTREAM_PORT = 8080
/** Lowest port for a LOCAL listener (the mesh-shared proxy). */
const val MIN_PROXY_PORT = 1024
const val MAX_PROXY_PORT = 65535
/** A remote proxy may listen on any TCP port. */
const val MIN_REMOTE_PROXY_PORT = 1

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
          forced = store.get(KEY_UP_FORCED) == "1",
          // Unset means ON: forced mode fails closed unless the user turns it off.
          failClosed = store.get(KEY_UP_FAILCLOSED) != "0",
          serverName = store.get(KEY_UP_SERVERNAME).orEmpty(),
          backend = OverlayBackend.entries.firstOrNull { it.name == store.get(KEY_UP_BACKEND) } ?: OverlayBackend.NONE,
          outproxy = store.get(KEY_UP_OUTPROXY).orEmpty(),
      )

  fun saveUpstream(cfg: UpstreamProxyConfig) {
    store.put(KEY_UP_KIND, cfg.kind.name)
    store.put(KEY_UP_HOST, cfg.host.trim())
    store.put(KEY_UP_PORT, cfg.port.toString())
    store.put(KEY_UP_USER, cfg.username)
    store.put(KEY_UP_PASS, cfg.password)
    store.put(KEY_UP_FORCED, if (cfg.forced) "1" else "0")
    store.put(KEY_UP_FAILCLOSED, if (cfg.failClosed) "1" else "0")
    store.put(KEY_UP_SERVERNAME, cfg.serverName.trim())
    store.put(KEY_UP_BACKEND, cfg.backend.name)
    store.put(KEY_UP_OUTPROXY, cfg.outproxy.trim())
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
    private const val KEY_UP_FORCED = "proxy.up.forced"
    private const val KEY_UP_FAILCLOSED = "proxy.up.failclosed"
    private const val KEY_UP_SERVERNAME = "proxy.up.servername"
    private const val KEY_UP_BACKEND = "proxy.up.backend"
    private const val KEY_UP_OUTPROXY = "proxy.up.outproxy"
  }
}

object ProxyPlanner {
  /** Local listener port (mesh-shared proxy): unprivileged only. */
  fun validPort(port: Int): Boolean = port in MIN_PROXY_PORT..MAX_PROXY_PORT

  /** Port of a REMOTE proxy endpoint: any TCP port 1–65535. */
  fun validRemotePort(port: Int): Boolean = port in MIN_REMOTE_PROXY_PORT..MAX_PROXY_PORT

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

  fun planOutbound(
      kind: UpstreamKind,
      host: String,
      port: Int,
      sdkInt: Int,
      forced: Boolean = false,
  ): OutboundPlan {
    if (kind == UpstreamKind.NONE || host.isBlank() || !validRemotePort(port)) {
      return OutboundPlan(applyHttpProxy = false, excludeHost = null, forced = false, reason = "disabled")
    }
    val exclude = host.takeIf { it.looksLikeLiteralIp() && !looksLikeMesh(it) && !isLoopback(it) }
    if (kind == UpstreamKind.SAM) {
      // SAM is not an HTTP/SOCKS proxy: Android cannot be told to use it. It only works
      // through the forced TUN path (or a per-app rule), by name, over streams.
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = exclude,
          forced = forced,
          reason =
              if (forced) "forced TUN path; I2P SAM streams only (no UDP)"
              else "SAM cannot be applied as an Android proxy; turn on forced routing to use it",
      )
    }
    if (kind == UpstreamKind.HTTPS && !forced) {
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = null,
          forced = false,
          reason = "Android's advisory proxy setting cannot do TLS to the proxy; use forced routing for HTTPS proxies",
      )
    }
    if (forced) {
      return OutboundPlan(
          applyHttpProxy = kind == UpstreamKind.HTTP && sdkInt >= 29,
          excludeHost = exclude,
          forced = true,
          reason = "forced TUN-to-proxy in libtailscale; credentials applied; fail-closed blocks leaks",
      )
    }
    if (kind == UpstreamKind.SOCKS5) {
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = null,
          forced = false,
          reason = "SOCKS5 cannot be applied to all apps; VpnService has no SOCKS API",
      )
    }
    if (sdkInt < 29) {
      return OutboundPlan(
          applyHttpProxy = false,
          excludeHost = null,
          forced = false,
          reason = "setHttpProxy requires Android 10",
      )
    }
    return OutboundPlan(
        applyHttpProxy = true,
        excludeHost = exclude,
        forced = false,
        reason = "advisory HTTP proxy via VpnService; apps may ignore it",
    )
  }

  private fun isLoopback(ip: String): Boolean = ip.startsWith("127.") || ip == "::1"

  private fun String.looksLikeLiteralIp(): Boolean =
      matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || contains(':')
}

data class OutboundPlan(
    val applyHttpProxy: Boolean,
    val excludeHost: String?,
    val forced: Boolean = false,
    val reason: String,
)

enum class ForcedDNSStatus {
  OFF,
  PENDING,
  UDP,
  TCP,
  BLOCKED;

  companion object {
    fun fromRaw(raw: String): ForcedDNSStatus =
        when (raw.lowercase()) {
          "pending" -> PENDING
          "udp" -> UDP
          "tcp" -> TCP
          "blocked" -> BLOCKED
          else -> OFF
        }
  }
}

enum class ForcedIPv6Status {
  OFF,
  UNKNOWN,
  VIA_PROXY,
  BLOCKED;

  companion object {
    fun fromRaw(raw: String): ForcedIPv6Status =
        when (raw.lowercase()) {
          "unknown" -> UNKNOWN
          "via_proxy" -> VIA_PROXY
          "blocked" -> BLOCKED
          else -> OFF
        }
  }
}
