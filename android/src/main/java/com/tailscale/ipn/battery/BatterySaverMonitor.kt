// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libtailscale.Libtailscale

/**
 * Watches battery, screen, and Android's own power saver while battery saver
 * is on, and tells the core to slow its background checks. Off by default.
 */
object BatterySaverMonitor {
  private const val TAG = "BatterySaver"

  private val _reason = MutableStateFlow(SaverReason.OFF)
  val reason: StateFlow<SaverReason> = _reason

  private val _power = MutableStateFlow(PowerState(-1, false, true, false))
  val power: StateFlow<PowerState> = _power

  @Volatile private var cfg: BatterySaverConfig? = null
  private var receiver: BroadcastReceiver? = null

  private fun store() = BatterySaverStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  fun config(): BatterySaverConfig = cfg ?: runCatching { store().load() }.getOrDefault(BatterySaverConfig()).also { cfg = it }

  fun effects(): SaverEffects = BatterySaverPlanner.effects(_reason.value != SaverReason.OFF)

  fun save(ctx: Context, c: BatterySaverConfig) {
    store().save(c)
    cfg = BatterySaverPlanner.sanitize(c)
    start(ctx)
  }

  /** Registers listeners when on; unregisters and returns to normal when off. */
  @Synchronized
  fun start(ctx: Context) {
    val app = ctx.applicationContext
    if (!config().enabled) {
      receiver?.let { runCatching { app.unregisterReceiver(it) } }
      receiver = null
      update(app, null)
      return
    }
    if (receiver == null) {
      val r =
          object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = update(c.applicationContext, i)
          }
      val filter =
          IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
          }
      val sticky = runCatching { ContextCompat.registerReceiver(app, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
      if (sticky.isSuccess) receiver = r else TSLog.w(TAG, "receiver not registered: ${sticky.exceptionOrNull()?.message}")
      update(app, sticky.getOrNull())
    } else {
      update(app, null)
    }
  }

  private fun update(ctx: Context, intent: Intent?) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val prev = _power.value
    val battery =
        if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
          val pct =
              BatterySaverPlanner.percent(
                  intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
          val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
          val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
          pct to charging
        } else {
          prev.batteryPercent to prev.charging
        }
    val state =
        PowerState(
            batteryPercent = battery.first,
            charging = battery.second,
            screenOn = pm?.isInteractive ?: true,
            systemSaver = pm?.isPowerSaveMode ?: false,
        )
    _power.value = state
    val r = BatterySaverPlanner.reason(config(), state)
    if (r != _reason.value) TSLog.d(TAG, "saver: $r")
    _reason.value = r
    runCatching { Libtailscale.setPowerSaver(r != SaverReason.OFF) }
  }
}
