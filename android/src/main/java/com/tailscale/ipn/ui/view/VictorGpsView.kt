// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.viewModel.VictorGpsViewModel
import com.tailscale.ipn.victorgps.VICTOR_GPS_DEBUG_CERT
import com.tailscale.ipn.victorgps.VICTOR_GPS_PACKAGE
import com.victorious.mesh.R

@Composable
fun VictorGpsView(
    onBack: BackNavigation,
    model: VictorGpsViewModel = viewModel(),
) {
  val profile by model.profile.collectAsState()
  val message by model.message.collectAsState()
  var lat by remember(profile.latitude) { mutableStateOf(profile.latitude) }
  var lng by remember(profile.longitude) { mutableStateOf(profile.longitude) }
  var target by remember(profile.targetPackage) { mutableStateOf(profile.targetPackage) }

  Scaffold(topBar = { Header(R.string.victorgps_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonGpsCard {
        Text(
            stringResource(R.string.victorgps_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.victorgps_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            titleRes = R.string.victorgps_enable,
            subtitle =
                stringResource(
                    if (profile.enabled) R.string.victorgps_on else R.string.victorgps_off
                ),
            isOn = profile.enabled,
            onToggle = { model.save(it, lat, lng, target) },
        )
        OutlinedTextField(
            lat,
            { lat = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.victorgps_lat)) },
            singleLine = true,
        )
        OutlinedTextField(
            lng,
            { lng = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.victorgps_lng)) },
            singleLine = true,
        )
        OutlinedTextField(
            target,
            { target = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.victorgps_target)) },
            singleLine = true,
        )
        TextButton(onClick = { model.save(profile.enabled, lat, lng, target) }) {
          Text(stringResource(R.string.victorgps_save))
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(R.string.victorgps_pair, VICTOR_GPS_PACKAGE, VICTOR_GPS_DEBUG_CERT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.victorgps_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun NeonGpsCard(content: @Composable () -> Unit) {
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
