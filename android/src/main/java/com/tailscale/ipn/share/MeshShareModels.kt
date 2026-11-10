// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.share

import com.tailscale.ipn.wireguard.SecretStore
import java.security.SecureRandom
import java.util.Base64

data class MeshShareConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_SHARE_PORT,
    val password: String = "",
) {
  override fun toString(): String = "MeshShareConfig(enabled=$enabled, port=$port, password=***)"
}

const val DEFAULT_SHARE_PORT = 8090
const val MIN_SHARE_PASSWORD = 16
/** Total size cap for the shared folder, so it cannot fill the phone. */
const val MAX_SHARE_BYTES: Long = 2L * 1024 * 1024 * 1024

class MeshShareStore(private val store: SecretStore, private val random: SecureRandom = SecureRandom()) {
  fun config(): MeshShareConfig =
      MeshShareConfig(
          enabled = store.get(KEY_ENABLED) == "1",
          port = store.get(KEY_PORT)?.toIntOrNull() ?: DEFAULT_SHARE_PORT,
          password = password(),
      )

  fun setEnabled(on: Boolean) = store.put(KEY_ENABLED, if (on) "1" else "0")

  fun setPort(port: Int) = store.put(KEY_PORT, port.toString())

  /** Share password, generated on first use and kept in encrypted prefs. */
  fun password(): String {
    val cur = store.get(KEY_PASSWORD)
    if (cur != null && MeshSharePlanner.validPassword(cur)) return cur
    return newPassword()
  }

  fun newPassword(): String {
    val bytes = ByteArray(18)
    random.nextBytes(bytes)
    val p = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    store.put(KEY_PASSWORD, p)
    return p
  }

  companion object {
    const val KEY_ENABLED = "share.enabled"
    const val KEY_PORT = "share.port"
    const val KEY_PASSWORD = "share.password"
  }
}

object MeshSharePlanner {
  fun validPort(port: Int): Boolean = port in 1024..65535

  fun validPassword(p: String): Boolean =
      p.length >= MIN_SHARE_PASSWORD && p.none { it.isWhitespace() || it == ':' }

  /** Same rules as the Go side: a plain name, no folders, not hidden. */
  fun safeFileName(raw: String?): String? {
    val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    val cleaned = base.filter { it.code >= 0x20 && it != ':' && it != '*' && it != '?' && it != '"' && it != '<' && it != '>' && it != '|' }
    if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.startsWith(".")) return null
    return cleaned.take(120)
  }

  /** "name.txt" → "name (2).txt" until it does not clash. */
  fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 2
    while ("$stem ($i)$ext" in taken) i++
    return "$stem ($i)$ext"
  }

  fun fitsCap(current: Long, adding: Long, cap: Long = MAX_SHARE_BYTES): Boolean =
      adding >= 0 && current >= 0 && current + adding <= cap

  fun shareUrl(meshIp: String, port: Int): String = "http://$meshIp:$port/"

  /** First mesh IPv4 of a peer ("100.x.y.z/32" → "100.x.y.z"). */
  fun peerIPv4(addresses: List<String>?): String? =
      addresses.orEmpty().map { it.substringBefore('/') }.firstOrNull { ip ->
        val p = ip.split('.')
        p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: -1) in 64..127
      }
}
