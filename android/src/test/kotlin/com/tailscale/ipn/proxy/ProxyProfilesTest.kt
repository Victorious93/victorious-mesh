// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

import com.tailscale.ipn.routing.PerAppAction
import com.tailscale.ipn.routing.PerAppExitPlanner
import com.tailscale.ipn.routing.PerAppRule
import com.tailscale.ipn.routing.ResolvedAppRoute
import com.tailscale.ipn.routing.AppRouteKind
import com.tailscale.ipn.wireguard.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProfilesTest {
  private class MapStore : SecretStore {
    val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
  }

  @Test
  fun settingsFromBeforeTheTorI2PReleaseLoadUnchanged() {
    val raw = MapStore()
    // Exactly the keys the previous release wrote.
    raw.m["proxy.up.kind"] = "SOCKS5"
    raw.m["proxy.up.host"] = "203.0.113.5"
    raw.m["proxy.up.port"] = "1080"
    raw.m["proxy.up.user"] = "u"
    raw.m["proxy.up.pass"] = "p"
    raw.m["proxy.up.forced"] = "1"
    raw.m["proxy.up.failclosed"] = "0"
    val up = ProxyStore(raw).upstream()
    assertEquals(UpstreamKind.SOCKS5, up.kind)
    assertEquals("203.0.113.5", up.host)
    assertTrue(up.forced)
    assertFalse("the user's explicit fail-open choice must survive migration", up.failClosed)
    assertEquals(OverlayBackend.NONE, up.backend)
    assertEquals("", up.serverName)
    assertEquals("", up.outproxy)
  }

  @Test
  fun savedProxyListFromBeforeTheNewFieldsStillDecodes() {
    val raw = MapStore()
    raw.m[SavedProxyStore.KEY] =
        """[{"id":"a","name":"Home","kind":"SOCKS5","host":"198.51.100.7","port":1080,"username":"u","password":"p"}]"""
    val l = SavedProxyStore(raw).list()
    assertEquals(1, l.size)
    assertEquals("Home", l[0].name)
    assertEquals(OverlayBackend.NONE, l[0].backend)
    assertEquals("", l[0].serverName)
  }

  @Test
  fun newFieldsRoundTrip() {
    val raw = MapStore()
    val s = ProxyStore(raw)
    s.saveUpstream(UpstreamProxyConfig(UpstreamKind.HTTPS, "proxy.example", 443, "u", "p", forced = true, serverName = "proxy.example"))
    s.saveUpstream(s.upstream().copy(backend = OverlayBackend.NONE))
    val a = s.upstream()
    assertEquals(UpstreamKind.HTTPS, a.kind)
    assertEquals(443, a.port)
    assertEquals("proxy.example", a.serverName)
    s.saveUpstream(UpstreamProxyConfig(UpstreamKind.SOCKS5, "127.0.0.1", 9050, forced = true, backend = OverlayBackend.TOR))
    assertEquals(OverlayBackend.TOR, s.upstream().backend)
  }

  @Test
  fun remotePortsAcceptOneToSixtyFiveThousand() {
    for (p in listOf(1, 80, 443, 1024, 65535)) assertTrue("$p", ProxyPlanner.validRemotePort(p))
    for (p in listOf(0, -1, 65536)) assertFalse("$p", ProxyPlanner.validRemotePort(p))
    // The local listener keeps its floor.
    assertFalse(ProxyPlanner.validPort(80))
    val ok = SavedProxy("a", "n", UpstreamKind.HTTP, "proxy.example", 80)
    assertNull(SavedProxyPlanner.problem(ok))
  }

  @Test
  fun kindAndBackendCombinationsAreValidated() {
    fun p(kind: UpstreamKind, backend: OverlayBackend = OverlayBackend.NONE, tls: String = "", out: String = "") =
        SavedProxy("a", "n", kind, "127.0.0.1", 9050, serverName = tls, backend = backend, outproxy = out)
    assertNull(SavedProxyPlanner.problem(p(UpstreamKind.SOCKS5, OverlayBackend.TOR)))
    assertNotNull("Tor needs SOCKS5", SavedProxyPlanner.problem(p(UpstreamKind.HTTP, OverlayBackend.TOR)))
    assertNull(SavedProxyPlanner.problem(p(UpstreamKind.SAM, OverlayBackend.I2P_SAM)))
    assertNotNull("SAM needs the I2P SAM backend", SavedProxyPlanner.problem(p(UpstreamKind.SAM)))
    assertNotNull(SavedProxyPlanner.problem(p(UpstreamKind.SOCKS5, OverlayBackend.I2P_SAM)))
    assertNull(SavedProxyPlanner.problem(p(UpstreamKind.HTTPS, tls = "proxy.example")))
    assertNotNull("TLS name only for https", SavedProxyPlanner.problem(p(UpstreamKind.SOCKS5, tls = "x")))
    assertNotNull("outproxy only for I2P", SavedProxyPlanner.problem(p(UpstreamKind.SOCKS5, OverlayBackend.TOR, out = "exit.i2p")))
    assertNull(SavedProxyPlanner.problem(p(UpstreamKind.SAM, OverlayBackend.I2P_SAM, out = "exit.i2p")))
  }

  @Test
  fun redactedExportNeverContainsSecrets() {
    val p = SavedProxy("a", "Work", UpstreamKind.HTTPS, "2001:db8::1", 8443, "alice", "hunter2", serverName = "proxy.example")
    val e = p.redactedExport()
    assertFalse(e.contains("hunter2"))
    assertFalse("the user name is not exported either", e.contains("alice"))
    assertTrue(e.contains("credentials=set (not exported)"))
    assertTrue(e.contains("endpoint=[2001:db8::1]:8443"))
    assertTrue(e.contains("tlsName=proxy.example"))
    assertFalse(p.toString().contains("hunter2"))
    assertFalse(UpstreamProxyConfig(UpstreamKind.HTTP, "h", 80, "alice", "hunter2").toString().contains("hunter2"))
  }

  @Test
  fun duplicateCopiesEverythingUnderANewId() {
    val st = SavedProxyStore(MapStore())
    val a = st.save(SavedProxy("", "Tor", UpstreamKind.SOCKS5, "127.0.0.1", 9050, backend = OverlayBackend.TOR))
    val b = st.duplicate(a.id)!!
    assertNotEquals(a.id, b.id)
    assertEquals("Tor (copy)", b.name)
    assertEquals(OverlayBackend.TOR, b.backend)
    assertEquals(2, st.list().size)
    st.delete(a.id)
    assertEquals(listOf(b.id), st.list().map { it.id })
    assertNull(st.duplicate("missing"))
  }

  @Test
  fun planOutboundIsHonestAboutAdvisoryVersusEnforced() {
    // https cannot be advisory: Android's proxy field has no TLS to the proxy.
    val adv = ProxyPlanner.planOutbound(UpstreamKind.HTTPS, "proxy.example", 443, 34)
    assertFalse(adv.applyHttpProxy)
    assertTrue(adv.reason.contains("forced routing"))
    assertTrue(ProxyPlanner.planOutbound(UpstreamKind.HTTPS, "proxy.example", 443, 34, forced = true).forced)
    val sam = ProxyPlanner.planOutbound(UpstreamKind.SAM, "127.0.0.1", 7656, 34)
    assertFalse(sam.applyHttpProxy)
    assertTrue(sam.reason.contains("forced"))
    // Loopback services (Orbot, i2pd) are never "excluded from the VPN".
    assertNull(ProxyPlanner.planOutbound(UpstreamKind.SOCKS5, "127.0.0.1", 9050, 34, forced = true).excludeHost)
    // A public literal is.
    assertEquals("203.0.113.5", ProxyPlanner.planOutbound(UpstreamKind.SOCKS5, "203.0.113.5", 1080, 34, forced = true).excludeHost)
    // Low ports are valid for remote proxies.
    assertTrue(ProxyPlanner.planOutbound(UpstreamKind.HTTP, "1.2.3.4", 80, 34).applyHttpProxy)
  }

  @Test
  fun perAppPolicyKeepsHttpsAsHttpsAndCarriesBackend() {
    val https = SavedProxy("h", "Corp", UpstreamKind.HTTPS, "proxy.example", 443, "u", "p", serverName = "proxy.example")
    val tor = SavedProxy("t", "Tor", UpstreamKind.SOCKS5, "127.0.0.1", 9050, backend = OverlayBackend.TOR)
    fun rule(pkg: String, uid: Int, p: SavedProxy) =
        PerAppRule(uid, ResolvedAppRoute(pkg, AppRouteKind.UPSTREAM_PROXY, PerAppAction.PROXY, proxy = p, reason = ""))
    val json = Json.parseToJsonElement(PerAppExitPlanner.policyJson(true, false, true, listOf(rule("a", 10001, https), rule("b", 10002, tor))))
    val rules = json.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    val h = rules.first { it["uid"]!!.jsonPrimitive.content == "10001" }["proxy"]!!.jsonObject
    assertEquals("an https proxy must never be sent to the core as socks5 or plain http", "https", h["kind"]!!.jsonPrimitive.content)
    assertEquals("proxy.example", h["serverName"]!!.jsonPrimitive.content)
    val t = rules.first { it["uid"]!!.jsonPrimitive.content == "10002" }["proxy"]!!.jsonObject
    assertEquals("tor", t["backend"]!!.jsonPrimitive.content)
    assertEquals("socks5", t["kind"]!!.jsonPrimitive.content)
  }

  @Test
  fun wireKindsMatchTheCore() {
    assertEquals("", UpstreamKind.NONE.wire)
    assertEquals(listOf("http", "https", "socks5", "sam"), listOf(UpstreamKind.HTTP, UpstreamKind.HTTPS, UpstreamKind.SOCKS5, UpstreamKind.SAM).map { it.wire })
    assertEquals(listOf("", "tor", "i2p-sam", "i2p-http"), OverlayBackend.entries.map { it.wire })
  }
}
