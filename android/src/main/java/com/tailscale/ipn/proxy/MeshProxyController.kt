// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.proxy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.tiles.ExtraTilesController
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.WireGuardManager
import com.victorious.mesh.R
import libtailscale.Libtailscale

/** Starts/stops the mesh-bound SOCKS5 + HTTP CONNECT listeners. Off by default. */
object MeshProxyController {
  private const val TAG = "MeshProxy"
  @Volatile private var lastStartKey: String? = null

  fun store(ctx: Context = App.get()): ProxyStore =
      ProxyStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  fun sync(ctx: Context) {
    val cfg = store(ctx).mesh()
    if (!cfg.enabled) {
      stop(ctx)
      return
    }
    if (WireGuardManager.isWireGuardActive()) {
      TSLog.w(TAG, "WireGuard mode is active; mesh proxy stays off")
      stop(ctx)
      return
    }
    val addrs = Notifier.netmap.value?.SelfNode?.Addresses.orEmpty()
    val ip = ProxyPlanner.firstMeshIPv4(addrs)
    if (ip == null || !ProxyPlanner.allowedMeshBind(ip, addrs)) {
      TSLog.w(TAG, "no mesh IP to bind; proxy stays off")
      stop(ctx)
      return
    }
    if (!ProxyPlanner.validPort(cfg.port)) {
      TSLog.w(TAG, "invalid proxy port")
      stop(ctx)
      return
    }
    if (!ProxyPlanner.authComplete(cfg.username, cfg.password)) {
      TSLog.w(TAG, "username and password must both be set or both empty")
      stop(ctx)
      return
    }
    val key = "$ip|${cfg.port}|${cfg.username}|${cfg.password}"
    val already =
        try {
          Libtailscale.meshProxyRunning() && Libtailscale.meshProxyAddr() == "$ip:${cfg.port}"
        } catch (_: Exception) {
          false
        }
    if (already && lastStartKey == key) {
      MeshProxyNotifier.show(ctx, ip, cfg.port)
      return
    }
    try {
      Libtailscale.startMeshProxy(ip, cfg.port, cfg.username, cfg.password)
      lastStartKey = key
      MeshProxyNotifier.show(ctx, ip, cfg.port)
      ExtraTilesController.refreshSurfaces(ctx)
    } catch (e: Exception) {
      TSLog.e(TAG, "start failed: ${e.message}")
      stop(ctx)
    }
  }

  fun stop(ctx: Context) {
    lastStartKey = null
    try {
      Libtailscale.stopMeshProxy()
    } catch (e: Exception) {
      TSLog.w(TAG, "stop: ${e.javaClass.simpleName}")
    }
    MeshProxyNotifier.cancel(ctx)
    ExtraTilesController.refreshSurfaces(ctx)
  }

  fun running(): Boolean =
      try {
        Libtailscale.meshProxyRunning()
      } catch (_: Exception) {
        false
      }

  fun syncForced(ctx: Context) {
    val cfg = store(ctx).upstream()
    if (WireGuardManager.isWireGuardActive() || !cfg.forced || cfg.kind == UpstreamKind.NONE || cfg.host.isBlank()) {
      clearForced()
      return
    }
    if (!ProxyPlanner.validPort(cfg.port) || !ProxyPlanner.authComplete(cfg.username, cfg.password)) {
      TSLog.w(TAG, "forced upstream rejected: port or auth")
      clearForced()
      return
    }
    val kind =
        when (cfg.kind) {
          UpstreamKind.HTTP -> "http"
          UpstreamKind.SOCKS5 -> "socks5"
          UpstreamKind.NONE -> ""
        }
    try {
      Libtailscale.setForcedUpstream(kind, cfg.host, cfg.port, cfg.username, cfg.password, cfg.failClosed)
    } catch (e: Exception) {
      TSLog.e(TAG, "forced upstream: ${e.message}")
      clearForced()
    }
  }

  fun clearForced() {
    try {
      Libtailscale.clearForcedUpstream()
    } catch (_: Exception) {
    }
  }

  fun forcedDNSState(): ForcedDNSStatus =
      try {
        ForcedDNSStatus.fromRaw(Libtailscale.forcedDNSState())
      } catch (_: Exception) {
        ForcedDNSStatus.OFF
      }

  fun forcedIPv6State(): ForcedIPv6Status =
      try {
        ForcedIPv6Status.fromRaw(Libtailscale.forcedIPv6State())
      } catch (_: Exception) {
        ForcedIPv6Status.OFF
      }
}

object MeshProxyNotifier {
  const val CHANNEL_ID = "victorious-mesh-proxy"

  fun show(ctx: Context, ip: String, port: Int) {
    ensureChannel(ctx)
    val open =
        PendingIntent.getActivity(
            ctx,
            0x7078,
            Intent(ctx, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val body = ctx.getString(R.string.proxy_notify_body, "$ip:$port")
    val n =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.proxy_notify_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    if (
        Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    ) {
      TSLog.w("MeshProxy", "POST_NOTIFICATIONS not granted; listener still running")
      return
    }
    NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
  }

  fun cancel(ctx: Context) {
    NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
  }

  fun ensureChannel(ctx: Context) {
    val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
    mgr.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.proxy_notify_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = ctx.getString(R.string.proxy_notify_channel_desc) }
    )
  }

  private const val NOTIF_ID = 0x70726F78
}
