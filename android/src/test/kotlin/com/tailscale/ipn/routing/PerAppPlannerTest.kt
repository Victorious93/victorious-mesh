package com.tailscale.ipn.routing

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppPlannerTest {
  @Test
  fun onlyDirectIsAppliedPerApp() {
    assertEquals(AppRouteKind.DIRECT, PerAppPlanner.applied(AppRoute("a", AppRouteKind.DIRECT)).applied)
    assertEquals(AppRouteKind.MESH, PerAppPlanner.applied(AppRoute("a", AppRouteKind.MESH)).applied)
    val exit = PerAppPlanner.applied(AppRoute("a", AppRouteKind.EXIT_NODE, "n1"))
    assertEquals(AppRouteKind.MESH, exit.applied)
    assertTrue(exit.reason.contains("device-wide"))
    val up = PerAppPlanner.applied(AppRoute("a", AppRouteKind.UPSTREAM_PROXY))
    assertEquals(AppRouteKind.MESH, up.applied)
    assertTrue(up.reason.contains("device-wide"))
  }

  @Test
  fun mergeExcludeModeAddsDirectApps() {
    val plan =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = listOf("com.sms"),
            allowSelected = false,
            directPackages = listOf("com.browser", "com.sms"),
        )
    assertFalse(plan.allowPackages)
    assertEquals(listOf("com.sms", "com.browser"), plan.packages)
  }

  @Test
  fun mergeIncludeModeRemovesDirectApps() {
    val plan =
        PerAppPlanner.mergeSplitTunnel(
            splitSelected = listOf("com.termux", "com.browser"),
            allowSelected = true,
            directPackages = listOf("com.browser"),
        )
    assertTrue(plan.allowPackages)
    assertEquals(listOf("com.termux"), plan.packages)
  }

  @Test
  fun storeRoundTripAndDefaultMesh() {
    val backing = MapStore()
    val s = PerAppStore(backing)
    assertEquals(AppRouteKind.MESH, s.routeFor("com.x").kind)
    s.setRoute(AppRoute("com.x", AppRouteKind.DIRECT))
    s.setRoute(AppRoute("com.y", AppRouteKind.EXIT_NODE, "peer1"))
    val again = PerAppStore(backing)
    assertEquals(AppRouteKind.DIRECT, again.routeFor("com.x").kind)
    assertEquals("peer1", again.routeFor("com.y").exitNodeId)
    assertEquals(DEFAULT_SIGHTING_ENDPOINT, again.sightingEndpoint())
    s.setSightingEndpoint(" https://example.test/ip ")
    assertEquals("https://example.test/ip", PerAppStore(backing).sightingEndpoint())
  }

  @Test
  fun sightingParserReadsIpAndCountry() {
    val a = IpSightingParser.parse("""{"ip":"203.0.113.9","country":"DE"}""")
    assertEquals("203.0.113.9", a.ip)
    assertEquals("DE", a.country)
    val b = IpSightingParser.parse("""{"query":"198.51.100.2","countryCode":"US"}""")
    assertEquals("198.51.100.2", b.ip)
    assertEquals("US", b.country)
    val c = IpSightingParser.parse("203.0.113.10\n")
    assertEquals("203.0.113.10", c.ip)
    assertNull(c.country)
    assertTrue(IpSightingParser.looksLikeMeshIPv4("100.64.1.2"))
    assertFalse(IpSightingParser.looksLikeMeshIPv4("203.0.113.9"))
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
