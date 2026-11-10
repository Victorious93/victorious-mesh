// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wol

import com.tailscale.ipn.NetworkChangeCallback
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends magic packets. Call off the main thread. */
object WolSender {
  /** LAN route: bound to the phone's Wi-Fi/cellular network (outside the VPN). */
  fun sendLan(d: WolDevice) {
    val network = NetworkChangeCallback.cachedDefaultNetwork ?: throw IllegalStateException("no Wi-Fi or cellular network")
    require(WakeOnLanPlanner.isLanAddress(d.address))
    DatagramSocket().use { s ->
      network.bindSocket(s)
      s.broadcast = true
      send(s, d)
    }
  }

  /** Mesh route: a normal socket, so it goes through the VPN like other app traffic. */
  fun sendMesh(d: WolDevice) {
    require(!WakeOnLanPlanner.isMeshAddress(d.address))
    DatagramSocket().use { s ->
      s.broadcast = true
      send(s, d)
    }
  }

  private fun send(s: DatagramSocket, d: WolDevice) {
    val payload = WakeOnLanPlanner.magicPacket(d.mac)
    val addr = InetAddress.getByName(d.address.trim()) // literal IPv4 only (validated)
    // Three copies: magic packets are often sent more than once.
    repeat(3) { s.send(DatagramPacket(payload, payload.size, addr, d.port)) }
  }
}
