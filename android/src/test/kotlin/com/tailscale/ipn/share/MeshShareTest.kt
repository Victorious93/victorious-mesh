// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.share

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshShareTest {
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
  fun offByDefaultWithGeneratedPassword() {
    val backing = MapStore()
    val s = MeshShareStore(backing)
    val cfg = s.config()
    assertFalse(cfg.enabled)
    assertEquals(DEFAULT_SHARE_PORT, cfg.port)
    assertTrue(MeshSharePlanner.validPassword(cfg.password))
    assertEquals("password is stable", cfg.password, MeshShareStore(backing).password())
    assertFalse("toString hides password", cfg.toString().contains(cfg.password))
    val next = s.newPassword()
    assertNotEquals(cfg.password, next)
  }

  @Test
  fun safeNames() {
    assertEquals("photo.jpg", MeshSharePlanner.safeFileName("photo.jpg"))
    assertEquals("x.txt", MeshSharePlanner.safeFileName("/storage/emulated/0/x.txt"))
    assertEquals("evil.sh", MeshSharePlanner.safeFileName("..\\..\\evil.sh"))
    assertNull(MeshSharePlanner.safeFileName(".hidden"))
    assertNull(MeshSharePlanner.safeFileName(".."))
    assertNull(MeshSharePlanner.safeFileName(""))
    assertNull(MeshSharePlanner.safeFileName(null))
    assertEquals("ab.txt", MeshSharePlanner.safeFileName("a<b>.txt"))
    assertEquals("ab.txt", MeshSharePlanner.safeFileName("a:b.txt"))
  }

  @Test
  fun uniqueNames() {
    assertEquals("a.txt", MeshSharePlanner.uniqueName("a.txt", emptySet()))
    assertEquals("a (2).txt", MeshSharePlanner.uniqueName("a.txt", setOf("a.txt")))
    assertEquals("a (3).txt", MeshSharePlanner.uniqueName("a.txt", setOf("a.txt", "a (2).txt")))
    assertEquals("README (2)", MeshSharePlanner.uniqueName("README", setOf("README")))
  }

  @Test
  fun capAndPorts() {
    assertTrue(MeshSharePlanner.fitsCap(0, 10, 10))
    assertFalse(MeshSharePlanner.fitsCap(5, 6, 10))
    assertFalse(MeshSharePlanner.fitsCap(0, -1, 10))
    assertTrue(MeshSharePlanner.validPort(8090))
    assertFalse(MeshSharePlanner.validPort(80))
    assertFalse(MeshSharePlanner.validPassword("short"))
    assertFalse(MeshSharePlanner.validPassword("has:colon-0123456789"))
  }

  @Test
  fun urlsUseMeshIPv4Only() {
    assertEquals("http://100.64.0.9:8090/", MeshSharePlanner.shareUrl("100.64.0.9", 8090))
    assertEquals("100.70.1.2", MeshSharePlanner.peerIPv4(listOf("fd7a:115c:a1e0::2/128", "100.70.1.2/32")))
    assertNull(MeshSharePlanner.peerIPv4(listOf("192.168.1.2/32")))
    assertNull(MeshSharePlanner.peerIPv4(null))
  }
}
