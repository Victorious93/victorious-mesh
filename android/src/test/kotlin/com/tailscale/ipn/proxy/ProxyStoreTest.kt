package com.tailscale.ipn.proxy

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStoreTest {
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

  @Test
  fun defaultsAreOff() {
    val s = ProxyStore(MapStore())
    assertFalse(s.mesh().enabled)
    assertEquals(DEFAULT_MESH_PORT, s.mesh().port)
    assertEquals(UpstreamKind.NONE, s.upstream().kind)
    assertEquals(DEFAULT_UPSTREAM_PORT, s.upstream().port)
  }

  @Test
  fun meshRoundTripUsesSameBackingStore() {
    val backing = MapStore()
    val s = ProxyStore(backing)
    s.saveMesh(MeshProxyConfig(enabled = true, port = 2080, username = "don", password = "s3cret"))
    val again = ProxyStore(backing).mesh()
    assertTrue(again.enabled)
    assertEquals(2080, again.port)
    assertEquals("don", again.username)
    assertEquals("s3cret", again.password)
    assertFalse("s3cret" in again.toString())
    assertFalse(again.toString().contains("don") && again.toString().contains("s3cret"))
  }

  @Test
  fun upstreamRoundTripAndUnknownKind() {
    val backing = MapStore()
    ProxyStore(backing)
        .saveUpstream(
            UpstreamProxyConfig(
                kind = UpstreamKind.HTTP,
                host = "10.1.2.3",
                port = 3128,
                username = "u",
                password = "p",
            )
        )
    val again = ProxyStore(backing).upstream()
    assertEquals(UpstreamKind.HTTP, again.kind)
    assertEquals("10.1.2.3", again.host)
    assertEquals(3128, again.port)
    assertEquals("u", again.username)
    assertEquals("p", again.password)
    assertFalse("p" in again.toString())

    backing.put("proxy.up.kind", "not-a-kind")
    assertEquals(UpstreamKind.NONE, ProxyStore(backing).upstream().kind)
  }

  @Test
  fun disableMeshClearsEnabledFlagOnly() {
    val backing = MapStore()
    val s = ProxyStore(backing)
    s.saveMesh(MeshProxyConfig(enabled = true, port = 9090, username = "a", password = "b"))
    s.saveMesh(s.mesh().copy(enabled = false))
    val again = ProxyStore(backing).mesh()
    assertFalse(again.enabled)
    assertEquals(9090, again.port)
    assertEquals("a", again.username)
  }
}
