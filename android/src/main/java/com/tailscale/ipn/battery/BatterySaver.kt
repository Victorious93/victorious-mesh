// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.battery

import com.tailscale.ipn.wireguard.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BatterySaverConfig(
    val enabled: Boolean = false,
    /** Saver turns on at or below this battery level (while not charging). */
    val lowBatteryPercent: Int = 20,
    val whenScreenOff: Boolean = true,
    val followSystemSaver: Boolean = true,
)

data class PowerState(
    /** 0–100, or -1 when unknown. */
    val batteryPercent: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val systemSaver: Boolean,
)

enum class SaverReason {
  OFF,
  LOW_BATTERY,
  SCREEN_OFF,
  SYSTEM_SAVER,
}

/**
 * What battery saver is allowed to change: only how often background checks
 * run. There is deliberately no field for the kill switch, fail-closed,
 * per-app rules, or the tunnel itself.
 */
data class SaverEffects(
    val proxyRecheckMinutes: Int,
    val taildropRefreshMs: Long,
    val themeRetryMultiplier: Int,
)

class BatterySaverStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun load(): BatterySaverConfig =
      store.get(KEY)?.let { runCatching { json.decodeFromString(BatterySaverConfig.serializer(), it) }.getOrNull() }
          ?.let { BatterySaverPlanner.sanitize(it) } ?: BatterySaverConfig()

  fun save(c: BatterySaverConfig) =
      store.put(KEY, json.encodeToString(BatterySaverConfig.serializer(), BatterySaverPlanner.sanitize(c)))

  companion object {
    const val KEY = "battery.config"
  }
}

object BatterySaverPlanner {
  val THRESHOLDS = listOf(10, 15, 20, 30)
  val NORMAL = SaverEffects(proxyRecheckMinutes = 3, taildropRefreshMs = 5_000, themeRetryMultiplier = 1)
  val SAVING = SaverEffects(proxyRecheckMinutes = 15, taildropRefreshMs = 30_000, themeRetryMultiplier = 4)

  fun sanitize(c: BatterySaverConfig): BatterySaverConfig = c.copy(lowBatteryPercent = c.lowBatteryPercent.coerceIn(5, 50))

  fun reason(c: BatterySaverConfig, s: PowerState): SaverReason {
    if (!c.enabled) return SaverReason.OFF
    val cfg = sanitize(c)
    return when {
      cfg.followSystemSaver && s.systemSaver -> SaverReason.SYSTEM_SAVER
      !s.charging && s.batteryPercent in 0..cfg.lowBatteryPercent -> SaverReason.LOW_BATTERY
      cfg.whenScreenOff && !s.screenOn -> SaverReason.SCREEN_OFF
      else -> SaverReason.OFF
    }
  }

  fun active(c: BatterySaverConfig, s: PowerState): Boolean = reason(c, s) != SaverReason.OFF

  fun effects(active: Boolean): SaverEffects = if (active) SAVING else NORMAL

  /** Battery level from ACTION_BATTERY_CHANGED extras; -1 when unknown. */
  fun percent(level: Int, scale: Int): Int = if (level < 0 || scale <= 0) -1 else (level * 100 / scale).coerceIn(0, 100)
}
