// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.dns

import com.tailscale.ipn.wireguard.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsToolsTest {
  private class MapStore : SecretStore {
    val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
  }

  private fun p(transport: DnsTransport, server: String, route: DnsRoute = DnsRoute.DIRECT, bootstrap: List<String> = emptyList(), tls: String = "") =
      ResolverProfile("x", "n", transport.wire, server, tls, bootstrap, route.wire)

  @Test
  fun profileValidation() {
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1")))
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "[2606:4700:4700::1111]:53")))
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.DOT, "1.1.1.1:853", tls = "one.one.one.one")))
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.DOH, "https://dns.example/dns-query", bootstrap = listOf("192.0.2.1"))))
    // Hostname servers need bootstrap IPs: they are never looked up with other DNS.
    assertTrue(DnsToolsPlanner.problem(p(DnsTransport.DOT, "dns.example"))!!.contains("bootstrap"))
    assertTrue(DnsToolsPlanner.problem(p(DnsTransport.DOH, "https://dns.example/q"))!!.contains("bootstrap"))
    // ...unless the route's far end resolves it.
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.DOT, "dns.example", route = DnsRoute.PROXY)))
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.DOH, "https://dns.example/q", route = DnsRoute.TOR)))
    // Plain http DoH is never accepted.
    assertTrue(DnsToolsPlanner.problem(p(DnsTransport.DOH, "http://dns.example/q", bootstrap = listOf("192.0.2.1")))!!.contains("https"))
    // UDP cannot cross a proxy or Tor.
    assertTrue(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1", route = DnsRoute.PROXY))!!.contains("UDP"))
    assertTrue(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1", route = DnsRoute.TOR))!!.contains("UDP"))
    assertNull(DnsToolsPlanner.problem(p(DnsTransport.TCP, "1.1.1.1", route = DnsRoute.TOR)))
    // TLS name only where there is TLS; bad bootstrap rejected; blank name rejected.
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1", tls = "x")))
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.DOT, "dns.example", bootstrap = listOf("not-an-ip"))))
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1").copy(name = " ")))
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "")))
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1").copy(transport = "carrier-pigeon")))
    assertNotNull(DnsToolsPlanner.problem(p(DnsTransport.UDP, "1.1.1.1").copy(route = "wormhole")))
    // Presets are valid.
    DnsToolsPlanner.presets.forEach { assertNull(it.name, DnsToolsPlanner.problem(it)) }
    assertTrue(DnsToolsPlanner.presets.all { it.private })
  }

  @Test
  fun ipLiteralDetection() {
    for (ok in listOf("1.1.1.1", "255.255.255.255", "2001:db8::1", "[::1]", "fd7a:115c:a1e0::53")) assertTrue(ok, DnsToolsPlanner.isIpLiteral(ok))
    for (bad in listOf("256.1.1.1", "1.1.1", "dns.example", "", "1.1.1.1.1", "gggg::1")) assertFalse(bad, DnsToolsPlanner.isIpLiteral(bad))
  }

  @Test
  fun profileJsonMatchesTheCoreContract() {
    val j = Json.parseToJsonElement(DnsToolsPlanner.profileJson(p(DnsTransport.DOT, "dns.example:853", DnsRoute.PROXY, listOf("192.0.2.1", "2001:db8::1"), "dns.example"))).jsonObject
    assertEquals("dot", j["transport"]!!.jsonPrimitive.content)
    assertEquals("proxy", j["route"]!!.jsonPrimitive.content)
    assertEquals("dns.example", j["serverName"]!!.jsonPrimitive.content)
    assertEquals(2, j["bootstrap"]!!.jsonArray.size)
    assertNull(j["private"])
  }

  @Test
  fun localRulesParsing() {
    val r = DnsToolsPlanner.localPolicyJson("# comment\nNAS.lan. 192.168.1.10 fd00::10\n\nprinter 10.0.0.9 # trailing", "ok.ads.test", "*.ads.test\ntracker.test").getOrThrow()
    val j = Json.parseToJsonElement(r).jsonObject
    assertEquals(setOf("nas.lan", "printer"), j["hosts"]!!.jsonObject.keys)
    assertEquals(2, j["hosts"]!!.jsonObject["nas.lan"]!!.jsonArray.size)
    assertEquals(2, j["block"]!!.jsonArray.size)
    assertEquals(1, j["allow"]!!.jsonArray.size)
    assertTrue(DnsToolsPlanner.localPolicyJson("nas.lan", "", "").isFailure)
    assertTrue(DnsToolsPlanner.localPolicyJson("nas.lan not-an-ip", "", "").isFailure)
    assertTrue(DnsToolsPlanner.localPolicyJson("", "", "").isSuccess)
  }

  @Test
  fun storeKeepsPresetsReadOnlyAndSupportsDuplicateDelete() {
    val st = ResolverProfileStore(MapStore())
    assertEquals(DnsToolsPlanner.presets.map { it.id }, st.all().map { it.id })
    val a = st.save(p(DnsTransport.DOT, "1.1.1.1", tls = "one.one.one.one").copy(id = "", name = "Cloudflare DoT"))
    assertEquals(3, st.all().size)
    val b = st.duplicate(a.id)!!
    assertEquals("Cloudflare DoT (copy)", b.name)
    assertEquals(a.serverName, b.serverName)
    // Saving a preset makes a copy; the preset itself is never overwritten.
    val c = st.save(DnsToolsPlanner.presets[0].copy(name = "Mine"))
    assertTrue(c.id != DnsToolsPlanner.presets[0].id)
    assertEquals("Network DNS (this network's servers)", st.all().first().name)
    st.delete(a.id)
    assertFalse(st.list().any { it.id == a.id })
    // Unreadable stored data falls back to nothing rather than crashing.
    val bad = MapStore().also { it.m[ResolverProfileStore.KEY] = "{not json" }
    assertEquals(2, ResolverProfileStore(bad).all().size)
  }

  @Test
  fun describeSaysWhetherEncrypted() {
    assertTrue(DnsToolsPlanner.describe(p(DnsTransport.DOH, "https://x/q")).contains("certificate checked"))
    assertTrue(DnsToolsPlanner.describe(p(DnsTransport.UDP, "1.1.1.1")).contains("not encrypted"))
  }

  // ---- per-network DNS: no setting may look active while being ignored

  private val rule = DnsRule("r", DnsMatch.ANY_WIFI, servers = listOf("1.1.1.1"))
  private val wifi = CurrentNetwork(true, false, null)

  @Test
  fun effectiveStatusIsExplicit() {
    val on = PerNetworkDnsConfig(true, listOf(rule))
    assertEquals(DnsEffect.DISABLED, PerNetworkDnsPlanner.effective(PerNetworkDnsConfig(false, listOf(rule)), wifi, false).effect)
    assertEquals(DnsEffect.NO_VALID_RULES, PerNetworkDnsPlanner.effective(PerNetworkDnsConfig(true, listOf(rule.copy(servers = emptyList()))), wifi, false).effect)
    val applied = PerNetworkDnsPlanner.effective(on, wifi, false)
    assertEquals(DnsEffect.ACTIVE, applied.effect)
    assertTrue(applied.applied)
    assertEquals(rule, applied.rule)
    assertEquals(DnsEffect.NO_MATCH, PerNetworkDnsPlanner.effective(on, CurrentNetwork(false, true, null), false).effect)
    // Forced proxy: valid rules exist, but they are NOT applied, and the status says so.
    val paused = PerNetworkDnsPlanner.effective(on, wifi, true)
    assertEquals(DnsEffect.PAUSED_FORCED_PROXY, paused.effect)
    assertFalse(paused.applied)
    assertNull(paused.rule)
    // The planner and the status agree: pick() and effective() never disagree about "applied".
    for (forced in listOf(false, true)) for (net in listOf(wifi, CurrentNetwork(false, true, null))) {
      assertEquals(PerNetworkDnsPlanner.pick(on, net, forced) != null, PerNetworkDnsPlanner.effective(on, net, forced).applied)
    }
  }
}
