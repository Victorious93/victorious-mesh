package com.tailscale.ipn.autoconnect

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectStoreTest {
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
    val s = AutoConnectStore(MapStore())
    assertFalse(s.enabled())
    assertEquals(AutoConnectConfig(), s.config())
  }

  @Test
  fun enabledRoundTrip() {
    val backing = MapStore()
    AutoConnectStore(backing).setEnabled(true)
    assertTrue(AutoConnectStore(backing).enabled())
    AutoConnectStore(backing).setEnabled(false)
    assertFalse(AutoConnectStore(backing).enabled())
  }
}
