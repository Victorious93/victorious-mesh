// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.tiles

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.killswitch.KillSwitchController
import com.tailscale.ipn.killswitch.KillSwitchEvent
import com.tailscale.ipn.proxy.MeshProxyController
import com.tailscale.ipn.proxy.ProxyPlanner
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.WireGuardManager

/** Enables extra tiles/widget when the user turns them on. Off by default. */
object ExtraTilesController {
  private const val TAG = "ExtraTiles"

  const val ACTION_WIDGET_TOGGLE = "com.victorious.mesh.action.WIDGET_TOGGLE"

  fun store(): ExtraTilesStore = ExtraTilesStore.fromApp()

  fun sync(ctx: Context) {
    val enabled = runCatching { store().enabled() }.getOrDefault(false)
    val want = ExtraTilesPlanner.visibleComponents(enabled)
    setComponent(ctx, KillSwitchTileService::class.java, ExtraComponent.KILL_SWITCH_TILE in want)
    setComponent(ctx, ProxyShareTileService::class.java, ExtraComponent.PROXY_SHARE_TILE in want)
    setComponent(ctx, StatusTileService::class.java, ExtraComponent.STATUS_TILE in want)
    setComponent(ctx, MeshStatusWidget::class.java, ExtraComponent.HOME_WIDGET in want)
    if (enabled) {
      refreshSurfaces(ctx)
    }
  }

  fun refreshSurfaces(ctx: Context) {
    val enabled = runCatching { store().enabled() }.getOrDefault(false)
    if (!enabled) return
    MeshStatusWidget.updateAll(ctx)
    requestTile(ctx, KillSwitchTileService::class.java)
    requestTile(ctx, ProxyShareTileService::class.java)
    requestTile(ctx, StatusTileService::class.java)
  }

  fun apply(ctx: Context, decision: ExtraClickDecision) {
    when (decision.action) {
      ExtraClickAction.NONE -> Unit
      ExtraClickAction.TOGGLE_KILL_SWITCH_ON -> toggleKillSwitch(true)
      ExtraClickAction.TOGGLE_KILL_SWITCH_OFF -> toggleKillSwitch(false)
      ExtraClickAction.TOGGLE_PROXY_ON -> toggleProxy(ctx, true)
      ExtraClickAction.TOGGLE_PROXY_OFF -> toggleProxy(ctx, false)
      ExtraClickAction.OPEN_APP -> openApp(ctx)
      ExtraClickAction.START_VPN -> UninitializedApp.get().startVPN()
      ExtraClickAction.STOP_VPN -> UninitializedApp.get().stopVPN()
    }
    TSLog.d(TAG, "${decision.action}: ${decision.reason}")
    refreshSurfaces(ctx)
  }

  fun snapshot(): ExtraSurfaceSnapshot {
    val vpn =
        runCatching { App.get().getAppScopedViewModel().vpnActive.value }.getOrDefault(false)
    val addrs =
        runCatching { Notifier.netmap.value?.SelfNode?.Addresses.orEmpty() }.getOrDefault(emptyList())
    val ip = ProxyPlanner.firstMeshIPv4(addrs)
    val proxy = runCatching { MeshProxyController.running() }.getOrDefault(false)
    val kill = runCatching { KillSwitchController.store().enabled() }.getOrDefault(false)
    val wg = runCatching { WireGuardManager.isWireGuardActive() }.getOrDefault(false)
    return ExtraSurfaceSnapshot(
        connected = vpn,
        meshIpv4 = ip,
        proxyListening = proxy,
        killSwitchOn = kill,
        meshReady = vpn && !wg && !ip.isNullOrBlank(),
    )
  }

  fun widgetToggleIntent(ctx: Context): PendingIntent {
    val intent =
        Intent(ctx, MeshStatusWidget::class.java).apply { action = ACTION_WIDGET_TOGGLE }
    return PendingIntent.getBroadcast(
        ctx,
        0x74696C65,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun toggleKillSwitch(on: Boolean) {
    val store = KillSwitchController.store()
    store.setEnabled(on)
    val vpn = runCatching { App.get().getAppScopedViewModel().vpnActive.value }.getOrDefault(false)
    val event = if (on) KillSwitchEvent.TOGGLE_ON else KillSwitchEvent.TOGGLE_OFF
    KillSwitchController.apply(event, vpn)
  }

  private fun toggleProxy(ctx: Context, on: Boolean) {
    val store = MeshProxyController.store(ctx)
    store.saveMesh(store.mesh().copy(enabled = on))
    MeshProxyController.sync(ctx)
  }

  private fun openApp(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        ?: Intent(ctx, MainActivity::class.java)
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(launch)
  }

  private fun setComponent(ctx: Context, cls: Class<*>, enabled: Boolean) {
    val state =
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    ctx.packageManager.setComponentEnabledSetting(
        ComponentName(ctx, cls),
        state,
        PackageManager.DONT_KILL_APP,
    )
  }

  private fun requestTile(ctx: Context, cls: Class<out TileService>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
    runCatching {
      TileService.requestListeningState(ctx, ComponentName(ctx, cls))
    }
  }
}

data class ExtraSurfaceSnapshot(
    val connected: Boolean,
    val meshIpv4: String?,
    val proxyListening: Boolean,
    val killSwitchOn: Boolean,
    val meshReady: Boolean,
)
