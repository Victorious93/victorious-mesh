// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.shell

import com.tailscale.ipn.wireguard.SecretStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How to open the session. No passwords are ever stored: use SSH keys. */
enum class ShellClient {
  /** Any installed app that opens ssh:// links (ConnectBot, JuiceSSH, …). */
  SSH_APP,
  /** Copy the ssh command and open Termux. */
  TERMUX,
  /** Only show and copy the command. */
  COMMAND,
}

@Serializable
data class ShellTarget(
    val id: String,
    val name: String,
    /** Mesh node StableID; empty when [host] is used. */
    val nodeId: String = "",
    /** Mesh IP or MagicDNS name, used when the node is not in the netmap. */
    val host: String = "",
    val user: String = "",
    val port: Int = 22,
    val client: ShellClient = ShellClient.SSH_APP,
)

const val TERMUX_PACKAGE = "com.termux"
const val TERMUX_SSHD_PORT = 8022

class ShellTargetStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun list(): List<ShellTarget> {
    val raw = store.get(KEY) ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(ShellTarget.serializer()), raw) }
        .getOrDefault(emptyList())
  }

  fun save(t: ShellTarget): ShellTarget {
    val withId = if (t.id.isBlank()) t.copy(id = UUID.randomUUID().toString()) else t
    val clean = withId.copy(name = withId.name.trim(), host = withId.host.trim(), user = withId.user.trim())
    write(list().filterNot { it.id == clean.id } + clean)
    return clean
  }

  fun delete(id: String) = write(list().filterNot { it.id == id })

  private fun write(l: List<ShellTarget>) =
      store.put(KEY, json.encodeToString(ListSerializer(ShellTarget.serializer()), l))

  companion object {
    const val KEY = "shell.targets"
  }
}

object RemoteShellPlanner {
  private val USER = Regex("^[a-z_][a-z0-9_.-]{0,31}$", RegexOption.IGNORE_CASE)
  private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
  private val IPV6 = Regex("^[0-9A-Fa-f:]{2,39}$")

  fun validUser(u: String): Boolean = USER.matches(u)

  fun validHost(h: String): Boolean = HOST.matches(h) || (h.contains(':') && IPV6.matches(h))

  fun validPort(p: Int): Boolean = p in 1..65535

  /** First mesh IPv4 of a node ("100.x.y.z/32" → "100.x.y.z"). */
  fun meshIPv4(addresses: List<String>?): String? =
      addresses.orEmpty().map { it.substringBefore('/') }.firstOrNull { ip ->
        val p = ip.split('.')
        p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: -1) in 64..127
      }

  /** Null when usable; otherwise a short plain-language reason. */
  fun problem(t: ShellTarget, resolvedHost: String?): String? =
      when {
        t.name.isBlank() -> "name is empty"
        !validUser(t.user) -> "user name can only use letters, digits, _ . -"
        !validPort(t.port) -> "port must be 1–65535"
        resolvedHost.isNullOrBlank() -> "pick a mesh device or type its mesh address"
        !validHost(resolvedHost) -> "address has characters that are not allowed"
        else -> null
      }

  /** Mesh node first (current IP), then the typed host. */
  fun resolveHost(t: ShellTarget, nodeIp: (String) -> String?): String? =
      (if (t.nodeId.isNotEmpty()) nodeIp(t.nodeId) else null) ?: t.host.takeIf { it.isNotBlank() }

  /** Command text. Every part is validated first, so nothing needs shell quoting. */
  fun command(user: String, host: String, port: Int): String {
    require(validUser(user) && validHost(host) && validPort(port))
    return if (port == 22) "ssh $user@$host" else "ssh -p $port $user@$host"
  }

  /** ssh://user@host:port URI for SSH apps. */
  fun sshUri(user: String, host: String, port: Int): String {
    require(validUser(user) && validHost(host) && validPort(port))
    val h = if (host.contains(':')) "[$host]" else host
    return "ssh://$user@$h:$port"
  }
}
