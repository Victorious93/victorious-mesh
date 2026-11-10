// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.battery.BatterySaverPlanner
import com.tailscale.ipn.battery.SaverReason
import com.tailscale.ipn.ui.viewModel.BatterySaverViewModel
import com.victorious.mesh.R

@Composable
fun BatterySaverView(onBack: BackNavigation, model: BatterySaverViewModel = viewModel()) {
  val cfg by model.config.collectAsState()
  val reason by model.reason.collectAsState()
  val power by model.power.collectAsState()

  Scaffold(topBar = { Header(R.string.battery_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      SaverCard {
        Text(
            stringResource(R.string.battery_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.battery_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            R.string.battery_enable,
            subtitle = stringResource(R.string.battery_enable_subtitle),
            isOn = cfg.enabled,
            onToggle = { model.update(cfg.copy(enabled = it)) },
        )
        Text(stringResource(R.string.battery_threshold), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BatterySaverPlanner.THRESHOLDS.forEach { t ->
            FilterChip(
                selected = cfg.lowBatteryPercent == t,
                enabled = cfg.enabled,
                onClick = { model.update(cfg.copy(lowBatteryPercent = t)) },
                label = { Text("$t%") },
            )
          }
        }
        Setting.Switch(
            R.string.battery_screen_off,
            isOn = cfg.whenScreenOff,
            enabled = cfg.enabled,
            onToggle = { model.update(cfg.copy(whenScreenOff = it)) },
        )
        Setting.Switch(
            R.string.battery_system_saver,
            isOn = cfg.followSystemSaver,
            enabled = cfg.enabled,
            onToggle = { model.update(cfg.copy(followSystemSaver = it)) },
        )
      }

      SaverCard {
        Text(
            stringResource(
                when (reason) {
                  SaverReason.OFF -> R.string.battery_status_off
                  SaverReason.LOW_BATTERY -> R.string.battery_status_low
                  SaverReason.SCREEN_OFF -> R.string.battery_status_screen
                  SaverReason.SYSTEM_SAVER -> R.string.battery_status_system
                }),
            fontWeight = FontWeight.Bold,
            color = if (reason == SaverReason.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        )
        if (cfg.enabled && power.batteryPercent >= 0) {
          Text(
              stringResource(
                  if (power.charging) R.string.battery_level_charging else R.string.battery_level, power.batteryPercent),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(stringResource(R.string.battery_effects), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.battery_safety),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
      }

      Text(
          stringResource(R.string.battery_not_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SaverCard(content: @Composable () -> Unit) {
  val brush =
      Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
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
