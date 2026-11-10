// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.launch

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.killswitch.KillSwitchStore
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.util.TSLog
import com.victorious.mesh.R
import kotlinx.coroutines.delay

/**
 * Launcher controls (VictorLaunch / static shortcuts). Exported with no
 * permission, so ANY app can open it, which is why it never acts on its own:
 * it always shows Mesh's own confirm dialog, ignores every extra, and only a
 * tap on "Connect"/"Disconnect" in this dialog does anything. It does not use
 * or reopen the protected IPNReceiver.
 */
class MeshControlActivity : ComponentActivity() {
  private val TAG = "MeshControl"

  private val vpnConsent =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        run(MeshControlPlanner.afterVpnPermission(r.resultCode == RESULT_OK))
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Refuse taps that pass through an overlay drawn by another app.
    window.decorView.filterTouchesWhenObscured = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      runCatching { window.setHideOverlayWindows(true) }
    }
    show(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    show(intent)
  }

  private fun show(intent: Intent?) {
    val action = MeshControlPlanner.parse(intent?.action)
    if (action == null) {
      finish()
      return
    }
    val connected = runCatching { App.get().getAppScopedViewModel().vpnActive.value }.getOrDefault(false)
    val ks = runCatching { KillSwitchStore.fromApp().config() }.getOrNull()
    val warnKs = MeshControlPlanner.warnKillSwitch(action, ks?.enabled == true, ks?.armed == true)
    val done = MeshControlPlanner.alreadyDone(action, connected)
    val shownAt = SystemClock.elapsedRealtime()

    setContent {
      AppTheme {
        var armed by remember(shownAt) { mutableStateOf(false) }
        LaunchedEffect(shownAt) {
          delay(MeshControlPlanner.CONFIRM_DELAY_MS)
          armed = MeshControlPlanner.canConfirm(SystemClock.elapsedRealtime() - shownAt)
        }
        AlertDialog(
            onDismissRequest = { finish() },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = {
              Text(
                  stringResource(
                      if (action == MeshControlAction.CONNECT) R.string.meshctl_connect_title
                      else R.string.meshctl_disconnect_title),
                  fontWeight = FontWeight.Bold,
              )
            },
            text = {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when {
                          done && action == MeshControlAction.CONNECT -> R.string.meshctl_already_connected
                          done -> R.string.meshctl_already_disconnected
                          action == MeshControlAction.CONNECT -> R.string.meshctl_connect_body
                          else -> R.string.meshctl_disconnect_body
                        }))
                if (warnKs && !done) {
                  Text(stringResource(R.string.meshctl_killswitch_warning), color = MaterialTheme.colorScheme.secondary)
                }
              }
            },
            confirmButton = {
              if (done) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.meshctl_ok)) }
              } else {
                TextButton(
                    enabled = armed,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    onClick = { confirm(action) },
                ) {
                  Text(
                      stringResource(
                          if (action == MeshControlAction.CONNECT) R.string.meshctl_connect
                          else R.string.meshctl_disconnect),
                      color = if (action == MeshControlAction.CONNECT) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            },
            dismissButton = {
              if (!done) TextButton(onClick = { finish() }) { Text(stringResource(R.string.meshctl_cancel)) }
            },
        )
      }
    }
  }

  private fun confirm(action: MeshControlAction) {
    val app = App.get()
    val step =
        MeshControlPlanner.afterConfirm(
            action,
            confirmed = true,
            connected = app.getAppScopedViewModel().vpnActive.value,
            ableToStart = app.isAbleToStartVPN(),
            vpnPrepared = VpnService.prepare(this) == null,
        )
    run(step)
  }

  private fun run(step: MeshControlStep) {
    TSLog.d(TAG, "step: $step")
    when (step) {
      MeshControlStep.NOTHING -> finish()
      MeshControlStep.OPEN_APP_TO_SIGN_IN -> {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
      }
      MeshControlStep.REQUEST_VPN_PERMISSION -> {
        val consent = VpnService.prepare(this)
        if (consent == null) run(MeshControlStep.START_VPN) else vpnConsent.launch(consent)
      }
      MeshControlStep.START_VPN -> {
        App.get().startVPN()
        finish()
      }
      MeshControlStep.STOP_VPN -> {
        // Confirmed by the user in Mesh's own dialog: same as the in-app switch.
        App.get().stopVPN()
        finish()
      }
    }
  }
}
