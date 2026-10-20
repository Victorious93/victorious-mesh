// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.viewModel.AutoConnectViewModel
import com.victorious.mesh.R

@Composable
fun AutoConnectView(
    onBack: BackNavigation,
    model: AutoConnectViewModel = viewModel(),
) {
  val cfg by model.config.collectAsState()
  val ctx = LocalContext.current

  Scaffold(topBar = { Header(R.string.autoconnect_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonAutoConnectCard {
        Text(
            stringResource(R.string.autoconnect_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.autoconnect_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            titleRes = R.string.autoconnect_enable,
            subtitle =
                stringResource(
                    if (cfg.enabled) R.string.autoconnect_on else R.string.autoconnect_off
                ),
            isOn = cfg.enabled,
            onToggle = { model.setEnabled(it) },
        )
        Text(
            stringResource(R.string.autoconnect_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
              runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
              }
            }
        ) {
          Text(stringResource(R.string.killswitch_open_android_vpn))
        }
      }
    }
  }
}

@Composable
private fun NeonAutoConnectCard(content: @Composable () -> Unit) {
  val brush =
      Brush.horizontalGradient(
          listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
      )
  Box(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(brush)
          .padding(2.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(16.dp),
  ) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}
