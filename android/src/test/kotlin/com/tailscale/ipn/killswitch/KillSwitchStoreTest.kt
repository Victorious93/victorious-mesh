package com.tailscale.ipn.killswitch

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KillSwitchStoreTest {
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
    val s = KillSwitchStore(MapStore())
    assertFalse(s.enabled())
    assertFalse(s.armed())
    assertEquals(KillSwitchConfig(), s.config())
  }

  @Test
  fun enabledRoundTripAndDisablingClearsArmed() {
    val backing = MapStore()
    val s = KillSwitchStore(backing)
    s.setEnabled(true)
    s.setArmed(true)
    assertTrue(KillSwitchStore(backing).enabled())
    assertTrue(KillSwitchStore(backing).armed())

    s.setEnabled(false)
    assertFalse(KillSwitchStore(backing).enabled())
    assertFalse(KillSwitchStore(backing).armed())
  }

  @Test
  fun toStringHasNoSecrets() {
    val cfg = KillSwitchConfig(enabled = true, armed = true)
    assertEquals("KillSwitchConfig(enabled=true, armed=true)", cfg.toString())
  }
}
