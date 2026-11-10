// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

import com.tailscale.ipn.wireguard.SecretStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A named external proxy that per-app routing can send one app through.
 * Stored in encrypted prefs only; never in config backups.
 */
@Serializable
data class SavedProxy(
    val id: String,
    val name: String,
    val kind: UpstreamKind = UpstreamKind.SOCKS5,
    val host: String = "",
    val port: Int = DEFAULT_SAVED_PROXY_PORT,
    val username: String = "",
    val password: String = "",
) {
  override fun toString(): String =
      "SavedProxy(id=$id, name=$name, kind=$kind, host=$host, port=$port, username=${username.isNotEmpty()}, password=***)"
}

const val DEFAULT_SAVED_PROXY_PORT = 1080

/** Id used for the proxy on Settings → Proxy when a per-app rule points at it. */
const val MAIN_UPSTREAM_PROXY_ID = "main"

class SavedProxyStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun list(): List<SavedProxy> {
    val raw = store.get(KEY) ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(SavedProxy.serializer()), raw) }
        .getOrDefault(emptyList())
  }

  fun get(id: String): SavedProxy? = list().firstOrNull { it.id == id }

  /** Insert or replace by id. Returns the stored proxy (a new id is assigned if blank). */
  fun save(proxy: SavedProxy): SavedProxy {
    val withId = if (proxy.id.isBlank()) proxy.copy(id = UUID.randomUUID().toString()) else proxy
    val clean = withId.copy(name = withId.name.trim(), host = withId.host.trim())
    val next = list().filterNot { it.id == clean.id } + clean
    write(next)
    return clean
  }

  fun delete(id: String) {
    write(list().filterNot { it.id == id })
  }

  private fun write(list: List<SavedProxy>) {
    store.put(KEY, json.encodeToString(ListSerializer(SavedProxy.serializer()), list))
  }

  companion object {
    const val KEY = "proxy.saved.list"
  }
}

object SavedProxyPlanner {
  /** Null when usable; otherwise a short plain-language reason. */
  fun problem(p: SavedProxy): String? =
      when {
        p.kind == UpstreamKind.NONE -> "pick HTTP or SOCKS5"
        p.host.isBlank() -> "host is empty"
        p.host.trim().contains(' ') -> "host has a space"
        !ProxyPlanner.validPort(p.port) -> "port must be $MIN_PROXY_PORT–$MAX_PROXY_PORT"
        !ProxyPlanner.authComplete(p.username, p.password) -> "set both username and password, or neither"
        else -> null
      }

  /** The Settings → Proxy upstream as a per-app choice, if it is filled in. */
  fun fromMainUpstream(cfg: UpstreamProxyConfig): SavedProxy? {
    if (cfg.kind == UpstreamKind.NONE || cfg.host.isBlank()) return null
    return SavedProxy(
        id = MAIN_UPSTREAM_PROXY_ID,
        name = "Settings → Proxy",
        kind = cfg.kind,
        host = cfg.host,
        port = cfg.port,
        username = cfg.username,
        password = cfg.password,
    )
  }
}
