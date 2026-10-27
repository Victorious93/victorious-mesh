// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.fleet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tailscale.ipn.fleet.theme.FleetThemeClient
import com.tailscale.ipn.fleet.theme.FleetThemeContract

/**
 * Wake-up only. Extras are not the theme and never authorize. The client
 * re-queries the pinned provider for the full snapshot.
 */
class FleetThemeChangedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != FleetThemeContract.ACTION_CHANGED) return
    FleetThemeClient.refresh(context.applicationContext)
  }
}
