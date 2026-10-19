package com.tailscale.ipn.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WireGuardProfileStoreTest {
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

  private val conf = "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.0.0.2/32\n"

  @Test
  fun roundTripAddRenameDelete() {
    val backing = MapStore()
    val s = WireGuardProfileStore(backing)
    val p = s.add("office", conf)
    // A fresh store instance over the same secret storage sees the same data.
    val s2 = WireGuardProfileStore(backing)
    assertEquals(listOf(p), s2.list())
    assertEquals(conf, s2.config(p.id))
    s2.rename(p.id, "  home ")
    assertEquals("home", s.list().single().name)
    s.setMode(TunnelMode.WIREGUARD, p.id)
    assertEquals(TunnelMode.WIREGUARD, s2.mode())
    assertEquals(p.id, s2.activeProfileId())
    s.delete(p.id)
    assertEquals(emptyList<WireGuardProfile>(), s2.list())
    assertNull(s2.config(p.id))
    assertNull(s2.activeProfileId())
  }

  @Test
  fun privateKeyOnlyInConfigEntry() {
    val backing = MapStore()
    val s = WireGuardProfileStore(backing)
    val p = s.add("x", conf)
    val leaks = backing.m.filterKeys { it != "wg.conf.${p.id}" }.values.any { "PrivateKey" in it }
    assertFalse(leaks)
    assertFalse("PrivateKey" in p.toString())
  }

  @Test
  fun duplicateNamesAndDefaults() {
    val s = WireGuardProfileStore(MapStore())
    assertEquals("a", s.add("a", conf).name)
    assertEquals("a (2)", s.add("a", conf).name)
    assertEquals("WireGuard", s.add("  ", conf).name)
    assertEquals(TunnelMode.MESH, s.mode())
    assertEquals("office", WireGuardProfileStore.nameFromFileName("office.conf"))
    assertEquals("WireGuard", WireGuardProfileStore.nameFromFileName(null))
  }

  @Test
  fun corruptListIsTreatedAsEmpty() {
    val b = MapStore()
    b.m["wg.profiles"] = "not json"
    assertEquals(emptyList<WireGuardProfile>(), WireGuardProfileStore(b).list())
  }
}
