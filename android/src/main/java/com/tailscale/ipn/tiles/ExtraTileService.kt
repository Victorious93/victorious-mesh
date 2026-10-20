// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.victorious.mesh.R

/** Shared extra-tile behaviour. Components stay disabled until Settings turns them on. */
abstract class ExtraTileService : TileService() {
  protected abstract val kind: ExtraTileKind

  override fun onStartListening() {
    paint()
  }

  override fun onClick() {
    unlockAndRun { secureOnClick() }
  }

  private fun secureOnClick() {
    val extras = ExtraTilesController.store().enabled()
    val snap = ExtraTilesController.snapshot()
    val decision =
        ExtraTilesPlanner.decideTileClick(
            kind = kind,
            extrasEnabled = extras,
            killSwitchOn = snap.killSwitchOn,
            proxyOn = snap.proxyListening,
            meshReady = snap.meshReady,
        )
    if (decision.action == ExtraClickAction.OPEN_APP) {
      launchApp()
      return
    }
    ExtraTilesController.apply(this, decision)
    paint()
  }

  private fun paint() {
    val tile = qsTile ?: return
    val extras = runCatching { ExtraTilesController.store().enabled() }.getOrDefault(false)
    val snap = ExtraTilesController.snapshot()
    when (kind) {
      ExtraTileKind.KILL_SWITCH -> {
        tile.label = getString(R.string.tile_killswitch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle =
              getString(if (snap.killSwitchOn) R.string.tile_on else R.string.tile_off)
        }
        tile.state =
            if (extras && snap.killSwitchOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      }
      ExtraTileKind.PROXY_SHARE -> {
        tile.label = getString(R.string.tile_proxy_share)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle =
              getString(if (snap.proxyListening) R.string.tile_on else R.string.tile_off)
        }
        tile.state =
            if (extras && snap.proxyListening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      }
      ExtraTileKind.STATUS -> {
        tile.label = getString(R.string.tile_status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          tile.subtitle = ExtraTilesPlanner.statusLine(snap.connected, snap.meshIpv4)
        }
        tile.state = if (extras && snap.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
      }
    }
    tile.updateTile()
  }

  @Suppress("DEPRECATION")
  private fun launchApp() {
    val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(
          PendingIntent.getActivity(
              this,
              0,
              i,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      )
    } else {
      startActivityAndCollapse(i)
    }
  }
}

class KillSwitchTileService : ExtraTileService() {
  override val kind = ExtraTileKind.KILL_SWITCH
}

class ProxyShareTileService : ExtraTileService() {
  override val kind = ExtraTileKind.PROXY_SHARE
}

class StatusTileService : ExtraTileService() {
  override val kind = ExtraTileKind.STATUS
}
