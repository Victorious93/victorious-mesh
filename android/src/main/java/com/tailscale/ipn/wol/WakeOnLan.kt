// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wol

import com.tailscale.ipn.wireguard.SecretStore
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How the magic packet reaches the sleeping device. */
enum class WolRoute {
  /** Broadcast on the Wi-Fi this phone is on (outside the VPN, LAN addresses only). */
  LAN,
  /** Send over the mesh to an address behind a subnet router (unicast or directed broadcast). */
  MESH_SUBNET,
  /** Show a command to run on a mesh device that sits on that LAN. */
  PEER_COMMAND,
}

@Serializable
data class WolDevice(
    val id: String,
    val name: String,
    val mac: String,
    /** LAN: broadcast address (default 255.255.255.255). MESH_SUBNET: target or directed-broadcast IP. */
    val address: String = WakeOnLanPlanner.LIMITED_BROADCAST,
    val port: Int = 9,
    val route: WolRoute = WolRoute.LAN,
    /** Mesh node on that LAN, for PEER_COMMAND. */
    val peerNodeId: String = "",
)

class WolStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun list(): List<WolDevice> {
    val raw = store.get(KEY) ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(WolDevice.serializer()), raw) }
        .getOrDefault(emptyList())
  }

  fun save(d: WolDevice): WolDevice {
    val withId = if (d.id.isBlank()) d.copy(id = UUID.randomUUID().toString()) else d
    val norm = WakeOnLanPlanner.normalizeMac(withId.mac) ?: withId.mac
    val clean = withId.copy(name = withId.name.trim(), mac = norm, address = withId.address.trim())
    write(list().filterNot { it.id == clean.id } + clean)
    return clean
  }

  fun delete(id: String) = write(list().filterNot { it.id == id })

  private fun write(l: List<WolDevice>) =
      store.put(KEY, json.encodeToString(ListSerializer(WolDevice.serializer()), l))

  companion object {
    const val KEY = "wol.devices"
  }
}

object WakeOnLanPlanner {
  const val LIMITED_BROADCAST = "255.255.255.255"
  private val HEX = Regex("^[0-9A-Fa-f]{12}$")

  /** "aa-bb-cc-dd-ee-ff", "AABB.CCDD.EEFF", "aabbccddeeff" → "AA:BB:CC:DD:EE:FF". */
  fun normalizeMac(raw: String): String? {
    val hex = raw.trim().replace(":", "").replace("-", "").replace(".", "").replace(" ", "")
    if (!HEX.matches(hex)) return null
    val up = hex.uppercase()
    if (up == "000000000000" || up == "FFFFFFFFFFFF") return null
    return up.chunked(2).joinToString(":")
  }

  fun macBytes(mac: String): ByteArray? =
      normalizeMac(mac)?.split(':')?.map { it.toInt(16).toByte() }?.toByteArray()

  /** 6 × 0xFF then the MAC 16 times (102 bytes). */
  fun magicPacket(mac: String): ByteArray {
    val m = requireNotNull(macBytes(mac)) { "bad MAC" }
    val out = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) out[i] = 0xFF.toByte()
    for (r in 0 until 16) System.arraycopy(m, 0, out, 6 + r * 6, 6)
    return out
  }

  fun parseIPv4(ip: String): IntArray? {
    val p = ip.trim().split('.')
    if (p.size != 4) return null
    val n = p.map { s -> s.toIntOrNull()?.takeIf { it in 0..255 && s == it.toString() } ?: return null }
    return n.toIntArray()
  }

  /** Private, link-local, or the limited broadcast: never a public address. */
  fun isLanAddress(ip: String): Boolean {
    val a = parseIPv4(ip) ?: return false
    return ip.trim() == LIMITED_BROADCAST ||
        a[0] == 10 ||
        (a[0] == 172 && a[1] in 16..31) ||
        (a[0] == 192 && a[1] == 168) ||
        (a[0] == 169 && a[1] == 254)
  }

  /** Mesh (100.64/10) addresses never take the LAN route. */
  fun isMeshAddress(ip: String): Boolean {
    val a = parseIPv4(ip) ?: return false
    return a[0] == 100 && a[1] in 64..127
  }

  /**
   * Null when the packet may be sent now; otherwise a plain reason.
   * The LAN route leaves outside the VPN, so it is refused while the kill
   * switch is holding traffic, and only goes to LAN addresses.
   */
  fun problem(d: WolDevice, killSwitchBlocking: Boolean, meshUp: Boolean): String? {
    if (d.name.isBlank()) return "name is empty"
    if (normalizeMac(d.mac) == null) return "MAC address looks wrong (6 pairs like AA:BB:CC:DD:EE:FF)"
    if (d.port !in 1..65535) return "port must be 1–65535"
    return when (d.route) {
      WolRoute.LAN ->
          when {
            !isLanAddress(d.address) -> "LAN broadcast must be a home-network address like 192.168.1.255"
            killSwitchBlocking -> "kill switch is blocking traffic until the mesh is back"
            else -> null
          }
      WolRoute.MESH_SUBNET ->
          when {
            parseIPv4(d.address) == null -> "type the device's LAN IP or the LAN broadcast"
            d.address.trim() == LIMITED_BROADCAST -> "255.255.255.255 cannot cross the mesh; use the LAN's own broadcast"
            isMeshAddress(d.address) -> "that is a mesh address; Wake-on-LAN needs the LAN address"
            !meshUp -> "connect the mesh first"
            else -> null
          }
      WolRoute.PEER_COMMAND -> if (d.peerNodeId.isEmpty()) "pick the mesh device on that LAN" else null
    }
  }

  /** Command to run on a Linux/Termux mesh device on the sleeping device's LAN. */
  fun peerCommand(d: WolDevice): String? {
    val mac = normalizeMac(d.mac) ?: return null
    val bcast = d.address.trim().takeIf { parseIPv4(it) != null && !isMeshAddress(it) } ?: LIMITED_BROADCAST
    return "wakeonlan -i $bcast -p ${d.port} $mac"
  }
}
