// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tailscale.ipn.App
import com.tailscale.ipn.util.TSLog

/**
 * Starts the last tunnel after boot when auto-connect is on. Android 12+
 * may refuse a background foreground-service start; Always-on VPN is
 * stronger. Off by default.
 */
class AutoConnectReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
    try {
      App.get()
      AutoConnectController.maybeStart(context.applicationContext, AutoConnectEvent.BOOT)
    } catch (e: Exception) {
      TSLog.e("AutoConnect", "boot start failed: ${e.message}")
    }
  }
}
