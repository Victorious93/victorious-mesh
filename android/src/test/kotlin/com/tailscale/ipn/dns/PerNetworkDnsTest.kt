// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.dns

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerNetworkDnsTest {
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

  private val home = DnsRule("h", DnsMatch.WIFI_SSID, "HomeNet", listOf("192.168.1.2"))
  private val wifi = DnsRule("w", DnsMatch.ANY_WIFI, servers = listOf("9.9.9.9"))
  private val cell = DnsRule("c", DnsMatch.CELLULAR, servers = listOf("1.1.1.1", "1.0.0.1"))
  private val cfg = PerNetworkDnsConfig(enabled = true, rules = listOf(home, wifi, cell))

  @Test
  fun offByDefault() {
    assertFalse(PerNetworkDnsConfig().enabled)
    assertNull(PerNetworkDnsPlanner.pick(PerNetworkDnsConfig(rules = cfg.rules), CurrentNetwork(true, false, "HomeNet")))
  }

  @Test
  fun pickOrder() {
    assertEquals(home, PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(true, false, "\"HomeNet\"")))
    assertEquals(wifi, PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(true, false, "Cafe")))
    assertEquals(wifi, PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(true, false, "<unknown ssid>")))
    assertEquals(wifi, PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(true, false, null)))
    assertEquals(cell, PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(false, true, null)))
    assertNull(PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(false, false, null)))
    assertNull(PerNetworkDnsPlanner.pick(cfg.copy(rules = listOf(home)), CurrentNetwork(true, false, "Cafe")))
  }

  @Test
  fun pausedWhileForcedProxyIsOn() {
    assertNull(PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(true, false, "HomeNet"), forcedProxy = true))
    assertNull(PerNetworkDnsPlanner.pick(cfg, CurrentNetwork(false, true, null), forcedProxy = true))
  }

  @Test
  fun serverValidation() {
    assertEquals(listOf("1.1.1.1", "9.9.9.9"), PerNetworkDnsPlanner.parseServers("1.1.1.1, 9.9.9.9"))
    assertEquals("100.64.0.5", PerNetworkDnsPlanner.parseServer("100.64.0.5"))
    assertNotNull(PerNetworkDnsPlanner.parseServer("2606:4700:4700::1111"))
    assertNull(PerNetworkDnsPlanner.parseServer("100.100.100.100"))
    assertNull(PerNetworkDnsPlanner.parseServer("127.0.0.1"))
    assertNull(PerNetworkDnsPlanner.parseServer("0.0.0.0"))
    assertNull(PerNetworkDnsPlanner.parseServer("224.0.0.251"))
    assertNull(PerNetworkDnsPlanner.parseServer("::1"))
    assertNull(PerNetworkDnsPlanner.parseServer("dns.google"))
    assertNull(PerNetworkDnsPlanner.parseServer("1.1.1.01"))
    assertNull(PerNetworkDnsPlanner.parseServer("256.1.1.1"))
    assertNull(PerNetworkDnsPlanner.parseServers(""))
    assertNull(PerNetworkDnsPlanner.parseServers("1.1.1.1 2.2.2.2 3.3.3.3 4.4.4.4 5.5.5.5"))
    assertNull(PerNetworkDnsPlanner.parseServers("1.1.1.1, nope"))
  }

  @Test
  fun applyKeepsSearchDomains() {
    assertEquals("8.8.8.8 \nlan", PerNetworkDnsPlanner.applyToBase("8.8.8.8 \nlan", null))
    assertEquals("9.9.9.9 \nlan", PerNetworkDnsPlanner.applyToBase("192.168.1.1 \nlan", wifi))
    assertEquals("1.1.1.1 1.0.0.1 ", PerNetworkDnsPlanner.applyToBase("10.0.0.1 ", cell))
  }

  @Test
  fun rulesNeedSsidAndServers() {
    assertNotNull(PerNetworkDnsPlanner.problem(home.copy(ssid = "")))
    assertNotNull(PerNetworkDnsPlanner.problem(home.copy(ssid = "x".repeat(33))))
    assertNotNull(PerNetworkDnsPlanner.problem(wifi.copy(servers = emptyList())))
    assertNull(PerNetworkDnsPlanner.problem(home))
    assertTrue(PerNetworkDnsPlanner.needsSsid(cfg))
    assertFalse(PerNetworkDnsPlanner.needsSsid(cfg.copy(rules = listOf(wifi, cell))))
  }

  @Test
  fun upsertReplacesSameNetwork() {
    var c = PerNetworkDnsConfig(enabled = true)
    c = PerNetworkDnsPlanner.upsert(c, DnsRule("", DnsMatch.CELLULAR, servers = listOf("1.1.1.1")))
    c = PerNetworkDnsPlanner.upsert(c, DnsRule("", DnsMatch.CELLULAR, servers = listOf("9.9.9.9")))
    assertEquals(1, c.rules.size)
    assertEquals(listOf("9.9.9.9"), c.rules.single().servers)
    c = PerNetworkDnsPlanner.upsert(c, DnsRule("", DnsMatch.WIFI_SSID, "\"Home\"", listOf("1.1.1.1")))
    assertEquals("Home", c.rules.first { it.match == DnsMatch.WIFI_SSID }.ssid)
  }

  @Test
  fun storeRoundTrip() {
    val b = MapStore()
    PerNetworkDnsStore(b).save(cfg)
    assertEquals(cfg, PerNetworkDnsStore(b).load())
    assertEquals(PerNetworkDnsConfig(), PerNetworkDnsStore(MapStore()).load())
  }
}
