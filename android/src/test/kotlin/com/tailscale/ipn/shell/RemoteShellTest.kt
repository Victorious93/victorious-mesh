// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.shell

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteShellTest {
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

  private val kali = ShellTarget("k", "Kali", nodeId = "nodeK", user = "kali", port = 22)

  @Test
  fun commandsAndUris() {
    assertEquals("ssh kali@100.64.0.5", RemoteShellPlanner.command("kali", "100.64.0.5", 22))
    assertEquals("ssh -p 8022 u0_a123@100.64.0.6", RemoteShellPlanner.command("u0_a123", "100.64.0.6", 8022))
    assertEquals("ssh://kali@100.64.0.5:22", RemoteShellPlanner.sshUri("kali", "100.64.0.5", 22))
    assertEquals("ssh://root@[fd7a:115c:a1e0::5]:22", RemoteShellPlanner.sshUri("root", "fd7a:115c:a1e0::5", 22))
    assertEquals("ssh root@kali.mesh.example", RemoteShellPlanner.command("root", "kali.mesh.example", 22))
  }

  @Test
  fun rejectsInjection() {
    for (bad in listOf("kali;rm -rf /", "a b", "\$(id)", "-oProxyCommand=x", "")) {
      assertFalse("user $bad", RemoteShellPlanner.validUser(bad))
    }
    for (bad in listOf("host;id", "-oProxyCommand=x", "a b", "x`id`", "")) {
      assertFalse("host $bad", RemoteShellPlanner.validHost(bad))
    }
    try {
      RemoteShellPlanner.command("kali", "h;id", 22)
      throw AssertionError("must refuse")
    } catch (_: IllegalArgumentException) {}
  }

  @Test
  fun resolvesNodeBeforeTypedHost() {
    val ip = RemoteShellPlanner.resolveHost(kali.copy(host = "10.0.0.1")) { if (it == "nodeK") "100.64.0.5" else null }
    assertEquals("100.64.0.5", ip)
    val fallback = RemoteShellPlanner.resolveHost(kali.copy(host = "kali.mesh")) { null }
    assertEquals("kali.mesh", fallback)
    assertNull(RemoteShellPlanner.resolveHost(kali) { null })
    assertNotNull(RemoteShellPlanner.problem(kali, null))
    assertNull(RemoteShellPlanner.problem(kali, "100.64.0.5"))
    assertNotNull(RemoteShellPlanner.problem(kali.copy(port = 0), "100.64.0.5"))
  }

  @Test
  fun storeRoundTripHasNoPasswordField() {
    val backing = MapStore()
    val s = ShellTargetStore(backing)
    val saved = s.save(kali.copy(id = "", name = " Kali ", client = ShellClient.TERMUX))
    assertTrue(saved.id.isNotBlank())
    assertEquals("Kali", ShellTargetStore(backing).list().single().name)
    assertEquals(ShellClient.TERMUX, ShellTargetStore(backing).list().single().client)
    assertFalse("no password is ever stored", backing.m.values.any { it.contains("password", ignoreCase = true) })
    s.delete(saved.id)
    assertTrue(s.list().isEmpty())
  }

  @Test
  fun meshIPv4() {
    assertEquals("100.70.1.2", RemoteShellPlanner.meshIPv4(listOf("fd7a:115c:a1e0::2/128", "100.70.1.2/32")))
    assertNull(RemoteShellPlanner.meshIPv4(listOf("10.0.0.1/32")))
  }
}
