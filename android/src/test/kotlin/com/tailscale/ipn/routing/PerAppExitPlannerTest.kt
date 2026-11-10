// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.routing

import com.tailscale.ipn.proxy.MAIN_UPSTREAM_PROXY_ID
import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.SavedProxyPlanner
import com.tailscale.ipn.proxy.SavedProxyStore
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.proxy.UpstreamProxyConfig
import com.tailscale.ipn.wireguard.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppExitPlannerTest {
  private val socks = SavedProxy("p1", "Home SOCKS", UpstreamKind.SOCKS5, "198.51.100.7", 1080)
  private val http = SavedProxy("p2", "Work HTTP", UpstreamKind.HTTP, "proxy.example", 8080, "u", "pw")
  private val proxies = listOf(socks, http)

  private fun resolve(
      route: AppRoute,
      enforce: Boolean = true,
      failClosed: Boolean = true,
      exit: String? = "exitA",
      sdk: Int = 34,
  ) = PerAppExitPlanner.resolve(route, enforce, failClosed, exit, proxies, sdk)

  @Test
  fun offByDefaultKeepsOldBehavior() {
    val r = resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p1"), enforce = false)
    assertEquals(PerAppAction.DEFAULT, r.action)
    assertTrue(r.reason.contains("device-wide"))
    assertEquals(PerAppAction.DEFAULT, resolve(AppRoute("a", AppRouteKind.EXIT_NODE, "exitA"), enforce = false).action)
    assertEquals(PerAppAction.BYPASS, resolve(AppRoute("a", AppRouteKind.DIRECT), enforce = false).action)
  }

  @Test
  fun oldAndroidCannotEnforce() {
    val r = resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p1"), sdk = 28)
    assertEquals(PerAppAction.DEFAULT, r.action)
    assertTrue(r.reason.contains("Android 10"))
    assertFalse(PerAppExitPlanner.supported(28))
    assertTrue(PerAppExitPlanner.supported(29))
  }

  @Test
  fun exitNodeMatchUsesEngine() {
    val r = resolve(AppRoute("a", AppRouteKind.EXIT_NODE, "exitA"))
    assertEquals(PerAppAction.ENGINE, r.action)
    assertFalse(r.warning)
  }

  @Test
  fun differentExitNodeBlocksWhenFailClosed() {
    val r = resolve(AppRoute("a", AppRouteKind.EXIT_NODE, "exitB"))
    assertEquals(PerAppAction.BLOCK, r.action)
    assertTrue(r.reason.contains("only one"))
    val none = resolve(AppRoute("a", AppRouteKind.EXIT_NODE, "exitB"), exit = null)
    assertEquals(PerAppAction.BLOCK, none.action)
    val unpicked = resolve(AppRoute("a", AppRouteKind.EXIT_NODE, ""))
    assertEquals(PerAppAction.BLOCK, unpicked.action)
  }

  @Test
  fun differentExitNodeFallsBackWhenFailOpen() {
    val r = resolve(AppRoute("a", AppRouteKind.EXIT_NODE, "exitB"), failClosed = false)
    assertEquals(PerAppAction.DEFAULT, r.action)
    assertTrue(r.warning)
  }

  @Test
  fun proxyChoice() {
    val r = resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p2"))
    assertEquals(PerAppAction.PROXY, r.action)
    assertEquals(http, r.proxy)
    assertTrue(r.reason.contains("blocked if it is down"))
    val open = resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p2"), failClosed = false)
    assertTrue(open.reason.contains("direct"))
  }

  @Test
  fun missingOrBrokenProxyBlocks() {
    assertEquals(PerAppAction.BLOCK, resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "gone")).action)
    // Empty proxyId means Settings → Proxy, which is not in this list.
    assertEquals(PerAppAction.BLOCK, resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY)).action)
    val bad = SavedProxy("p3", "Bad", UpstreamKind.SOCKS5, "198.51.100.9", 80)
    val r =
        PerAppExitPlanner.resolve(
            AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p3"), true, true, null, listOf(bad), 34)
    assertEquals(PerAppAction.BLOCK, r.action)
    assertTrue(r.reason.contains("port"))
  }

  @Test
  fun meshIsDefaultAndDirectIsBypass() {
    assertEquals(PerAppAction.DEFAULT, resolve(AppRoute("a", AppRouteKind.MESH)).action)
    assertEquals(PerAppAction.BYPASS, resolve(AppRoute("a", AppRouteKind.DIRECT)).action)
  }

  @Test
  fun capturedPackagesAreTheRoutedOnes() {
    val resolved =
        listOf(
            resolve(AppRoute("proxy", AppRouteKind.UPSTREAM_PROXY, proxyId = "p1")),
            resolve(AppRoute("exit", AppRouteKind.EXIT_NODE, "exitA")),
            resolve(AppRoute("blocked", AppRouteKind.EXIT_NODE, "exitZ")),
            resolve(AppRoute("normal", AppRouteKind.MESH)),
            resolve(AppRoute("direct", AppRouteKind.DIRECT)),
        )
    assertEquals(listOf("proxy", "exit", "blocked"), PerAppExitPlanner.capturedPackages(resolved))
  }

  @Test
  fun policyJsonShape() {
    val rules =
        listOf(
            PerAppRule(10100, resolve(AppRoute("a", AppRouteKind.UPSTREAM_PROXY, proxyId = "p2"))),
            PerAppRule(10101, resolve(AppRoute("b", AppRouteKind.EXIT_NODE, "exitA"))),
            PerAppRule(10102, resolve(AppRoute("c", AppRouteKind.EXIT_NODE, "nope"))),
            PerAppRule(10103, resolve(AppRoute("d", AppRouteKind.MESH))),
            PerAppRule(10104, resolve(AppRoute("e", AppRouteKind.DIRECT))),
        )
    val obj = Json.parseToJsonElement(PerAppExitPlanner.policyJson(true, true, true, rules)).jsonObject
    assertTrue(obj["enabled"]!!.jsonPrimitive.boolean)
    assertTrue(obj["exitNodeActive"]!!.jsonPrimitive.boolean)
    val arr = obj["rules"]!!.jsonArray
    assertEquals(3, arr.size) // default and bypass apps are not sent
    val first = arr[0].jsonObject
    assertEquals(10100, first["uid"]!!.jsonPrimitive.int)
    assertEquals("proxy", first["action"]!!.jsonPrimitive.content)
    val p = first["proxy"]!!.jsonObject
    assertEquals("http", p["kind"]!!.jsonPrimitive.content)
    assertEquals("proxy.example", p["host"]!!.jsonPrimitive.content)
    assertEquals(8080, p["port"]!!.jsonPrimitive.int)
    assertEquals("pw", p["password"]!!.jsonPrimitive.content)
    assertTrue(p["failClosed"]!!.jsonPrimitive.boolean)
    assertEquals("engine", arr[1].jsonObject["action"]!!.jsonPrimitive.content)
    assertEquals("block", arr[2].jsonObject["action"]!!.jsonPrimitive.content)
    assertNull(arr[1].jsonObject["proxy"])
  }

  @Test
  fun splitTunnelKeepsRoutedAppsInside() {
    val exclude =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = listOf("com.proxyapp", "com.other"),
            allowSelected = false,
            directPackages = listOf("com.direct"),
            capturedPackages = listOf("com.proxyapp"),
        )
    assertEquals(listOf("com.other", "com.direct"), exclude.packages)
    val include =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = listOf("com.termux"),
            allowSelected = true,
            directPackages = emptyList(),
            capturedPackages = listOf("com.proxyapp"),
        )
    assertEquals(listOf("com.termux", "com.proxyapp"), include.packages)
    val all =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = emptyList(),
            allowSelected = true,
            directPackages = emptyList(),
            capturedPackages = listOf("com.proxyapp"),
        )
    assertTrue("empty include list means all apps", all.packages.isEmpty())
    // Direct always wins over a stale captured entry.
    val both =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = emptyList(),
            allowSelected = false,
            directPackages = listOf("com.x"),
            capturedPackages = listOf("com.x"),
        )
    assertEquals(listOf("com.x"), both.packages)
  }

  @Test
  fun perAppStoreFlagsDefaults() {
    val s = PerAppStore(MapStore())
    assertFalse("enforcement is off by default", s.enforce())
    assertTrue("fail-closed is on by default", s.failClosed())
    s.setEnforce(true)
    s.setFailClosed(false)
    assertTrue(s.enforce())
    assertFalse(s.failClosed())
    s.setRoute(AppRoute("com.y", AppRouteKind.UPSTREAM_PROXY, proxyId = "p1"))
    assertEquals("p1", s.routeFor("com.y").proxyId)
  }

  @Test
  fun savedProxyStoreRoundTrip() {
    val backing = MapStore()
    val s = SavedProxyStore(backing)
    assertTrue(s.list().isEmpty())
    val a = s.save(SavedProxy("", " Home ", UpstreamKind.SOCKS5, " 198.51.100.7 ", 1080))
    assertTrue(a.id.isNotBlank())
    assertEquals("Home", a.name)
    assertEquals("198.51.100.7", a.host)
    s.save(a.copy(port = 1081))
    assertEquals(1, s.list().size)
    assertEquals(1081, SavedProxyStore(backing).get(a.id)!!.port)
    s.delete(a.id)
    assertTrue(s.list().isEmpty())
    assertFalse("password must not be in toString", http.toString().contains("pw"))
  }

  @Test
  fun savedProxyValidation() {
    assertNull(SavedProxyPlanner.problem(socks))
    assertNotNull(SavedProxyPlanner.problem(socks.copy(host = "")))
    assertNotNull(SavedProxyPlanner.problem(socks.copy(port = 80)))
    assertNotNull(SavedProxyPlanner.problem(socks.copy(username = "u")))
    assertNotNull(SavedProxyPlanner.problem(socks.copy(kind = UpstreamKind.NONE)))
    assertNull(SavedProxyPlanner.fromMainUpstream(UpstreamProxyConfig()))
    val main = SavedProxyPlanner.fromMainUpstream(UpstreamProxyConfig(UpstreamKind.HTTP, "10.9.9.9", 3128))
    assertEquals(MAIN_UPSTREAM_PROXY_ID, main!!.id)
  }

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
}
