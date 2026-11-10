// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.battery

import com.tailscale.ipn.wireguard.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySaverTest {
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

  private val on = BatterySaverConfig(enabled = true)
  private val awake = PowerState(batteryPercent = 80, charging = false, screenOn = true, systemSaver = false)

  @Test
  fun offByDefaultAndWhenDisabled() {
    assertFalse(BatterySaverConfig().enabled)
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(BatterySaverConfig(), awake.copy(batteryPercent = 5, screenOn = false, systemSaver = true)))
  }

  @Test
  fun triggers() {
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(on, awake))
    assertEquals(SaverReason.LOW_BATTERY, BatterySaverPlanner.reason(on, awake.copy(batteryPercent = 20)))
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(on, awake.copy(batteryPercent = 20, charging = true)))
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(on, awake.copy(batteryPercent = -1)))
    assertEquals(SaverReason.SCREEN_OFF, BatterySaverPlanner.reason(on, awake.copy(screenOn = false)))
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(on.copy(whenScreenOff = false), awake.copy(screenOn = false)))
    assertEquals(SaverReason.SYSTEM_SAVER, BatterySaverPlanner.reason(on, awake.copy(systemSaver = true)))
    assertEquals(SaverReason.OFF, BatterySaverPlanner.reason(on.copy(followSystemSaver = false), awake.copy(systemSaver = true)))
  }

  @Test
  fun effectsOnlySlowChecks() {
    val n = BatterySaverPlanner.effects(false)
    val s = BatterySaverPlanner.effects(true)
    assertEquals(3, n.proxyRecheckMinutes)
    assertEquals(15, s.proxyRecheckMinutes)
    assertTrue(s.taildropRefreshMs > n.taildropRefreshMs)
    assertTrue(s.themeRetryMultiplier > n.themeRetryMultiplier)
    // The effects type has no kill-switch, fail-closed, or per-app field.
    val names = SaverEffects::class.java.declaredFields.map { it.name.lowercase() }
    listOf("kill", "failclosed", "perapp", "block", "tunnel").forEach { bad ->
      assertTrue("unexpected field like $bad", names.none { it.contains(bad) })
    }
  }

  @Test
  fun thresholdsAndPercent() {
    assertEquals(5, BatterySaverPlanner.sanitize(on.copy(lowBatteryPercent = 0)).lowBatteryPercent)
    assertEquals(50, BatterySaverPlanner.sanitize(on.copy(lowBatteryPercent = 99)).lowBatteryPercent)
    assertEquals(50, BatterySaverPlanner.percent(50, 100))
    assertEquals(100, BatterySaverPlanner.percent(255, 255))
    assertEquals(-1, BatterySaverPlanner.percent(-1, 100))
    assertEquals(-1, BatterySaverPlanner.percent(10, 0))
  }

  @Test
  fun storeRoundTrip() {
    val b = MapStore()
    BatterySaverStore(b).save(on.copy(lowBatteryPercent = 15, whenScreenOff = false))
    assertEquals(on.copy(lowBatteryPercent = 15, whenScreenOff = false), BatterySaverStore(b).load())
    assertEquals(BatterySaverConfig(), BatterySaverStore(MapStore()).load())
  }
}
