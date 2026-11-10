// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.wol

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanTest {
  private class MapStore : SecretStore {
    val m = HashMap<String, String>()

    override fun get(key: String) = m[key]

    override fun put(key: String, value: String) {
      m[key] = value
    }

    override fun remove(key: String) {
      m.remove(key)
    }
  }

  private val pc = WolDevice("1", "Desktop", "aa-bb-cc-dd-ee-0f", "192.168.1.255", 9, WolRoute.LAN)

  @Test
  fun macNormalization() {
    assertEquals("AA:BB:CC:DD:EE:0F", WakeOnLanPlanner.normalizeMac("aa-bb-cc-dd-ee-0f"))
    assertEquals("AA:BB:CC:DD:EE:0F", WakeOnLanPlanner.normalizeMac("AABB.CCDD.EE0F"))
    assertEquals("AA:BB:CC:DD:EE:0F", WakeOnLanPlanner.normalizeMac(" aabbccddee0f "))
    assertNull(WakeOnLanPlanner.normalizeMac("aa:bb:cc"))
    assertNull(WakeOnLanPlanner.normalizeMac("zz:bb:cc:dd:ee:ff"))
    assertNull(WakeOnLanPlanner.normalizeMac("ff:ff:ff:ff:ff:ff"))
    assertNull(WakeOnLanPlanner.normalizeMac("00:00:00:00:00:00"))
  }

  @Test
  fun magicPacketLayout() {
    val p = WakeOnLanPlanner.magicPacket("01:02:03:04:05:06")
    assertEquals(102, p.size)
    for (i in 0 until 6) assertEquals(0xFF.toByte(), p[i])
    val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
    for (r in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + r * 6, 12 + r * 6))
  }

  @Test
  fun lanRouteOnlyHomeAddressesAndRespectsKillSwitch() {
    assertNull(WakeOnLanPlanner.problem(pc, killSwitchBlocking = false, meshUp = false))
    assertNull(WakeOnLanPlanner.problem(pc.copy(address = "255.255.255.255"), false, false))
    assertNotNull("public address refused", WakeOnLanPlanner.problem(pc.copy(address = "8.8.8.8"), false, false))
    assertNotNull("mesh address refused", WakeOnLanPlanner.problem(pc.copy(address = "100.64.0.1"), false, false))
    val blocked = WakeOnLanPlanner.problem(pc, killSwitchBlocking = true, meshUp = false)
    assertTrue(blocked!!.contains("kill switch"))
  }

  @Test
  fun meshRoute() {
    val d = pc.copy(route = WolRoute.MESH_SUBNET, address = "192.168.1.50")
    assertNull(WakeOnLanPlanner.problem(d, killSwitchBlocking = true, meshUp = true))
    assertNotNull(WakeOnLanPlanner.problem(d, false, meshUp = false))
    assertNotNull(WakeOnLanPlanner.problem(d.copy(address = "255.255.255.255"), false, true))
    assertNotNull(WakeOnLanPlanner.problem(d.copy(address = "100.70.0.2"), false, true))
    assertNotNull(WakeOnLanPlanner.problem(d.copy(address = "192.168.1"), false, true))
  }

  @Test
  fun peerCommand() {
    val d = pc.copy(route = WolRoute.PEER_COMMAND, peerNodeId = "nodeX")
    assertNull(WakeOnLanPlanner.problem(d, true, false))
    assertEquals("wakeonlan -i 192.168.1.255 -p 9 AA:BB:CC:DD:EE:0F", WakeOnLanPlanner.peerCommand(d))
    assertNotNull(WakeOnLanPlanner.problem(d.copy(peerNodeId = ""), false, true))
    assertEquals(
        "wakeonlan -i 255.255.255.255 -p 9 AA:BB:CC:DD:EE:0F",
        WakeOnLanPlanner.peerCommand(d.copy(address = "x; rm -rf /")))
  }

  @Test
  fun addressHelpers() {
    assertTrue(WakeOnLanPlanner.isLanAddress("10.1.2.3"))
    assertTrue(WakeOnLanPlanner.isLanAddress("172.20.0.255"))
    assertFalse(WakeOnLanPlanner.isLanAddress("172.32.0.1"))
    assertFalse(WakeOnLanPlanner.isLanAddress("192.168.01.1"))
    assertFalse(WakeOnLanPlanner.isLanAddress("300.1.1.1"))
  }

  @Test
  fun storeRoundTrip() {
    val backing = MapStore()
    val s = WolStore(backing)
    val saved = s.save(pc.copy(id = "", name = " Desktop "))
    assertEquals("AA:BB:CC:DD:EE:0F", saved.mac)
    assertEquals("Desktop", WolStore(backing).list().single().name)
    s.delete(saved.id)
    assertTrue(s.list().isEmpty())
  }
}
