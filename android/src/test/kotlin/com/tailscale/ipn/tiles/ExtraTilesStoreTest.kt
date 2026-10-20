package com.tailscale.ipn.tiles

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraTilesStoreTest {
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
  fun defaultsOff() {
    assertFalse(ExtraTilesStore(MapStore()).enabled())
  }

  @Test
  fun roundTripEnabled() {
    val backing = MapStore()
    ExtraTilesStore(backing).setEnabled(true)
    assertTrue(ExtraTilesStore(backing).enabled())
    ExtraTilesStore(backing).setEnabled(false)
    assertFalse(ExtraTilesStore(backing).enabled())
  }
}
