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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.connlog.ConnectionLogPlanner
import com.tailscale.ipn.connlog.LogKind
import com.tailscale.ipn.ui.viewModel.ConnectionLogViewModel
import com.victorious.mesh.R

@Composable
fun ConnectionLogView(onBack: BackNavigation, model: ConnectionLogViewModel = viewModel()) {
  val enabled by model.enabled.collectAsState()
  val entries by model.entries.collectAsState()
  val message by model.message.collectAsState()

  Scaffold(topBar = { Header(R.string.connlog_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      LogCard {
        Text(
            stringResource(R.string.connlog_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.connlog_body, ConnectionLogPlanner.MAX_ENTRIES),
            style = MaterialTheme.typography.bodyMedium,
        )
        Setting.Switch(
            R.string.connlog_enable,
            subtitle = stringResource(R.string.connlog_enable_subtitle),
            isOn = enabled,
            onToggle = { model.setEnabled(it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { model.refresh() }) { Text(stringResource(R.string.connlog_refresh)) }
          TextButton(onClick = { model.copy() }, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.connlog_copy)) }
          TextButton(onClick = { model.clear() }, enabled = entries.isNotEmpty()) {
            Text(stringResource(R.string.connlog_clear), color = MaterialTheme.colorScheme.secondary)
          }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
      }

      LogCard {
        if (entries.isEmpty()) {
          Text(stringResource(R.string.connlog_empty), style = MaterialTheme.typography.bodySmall)
        }
        entries.forEach { e ->
          Column {
            Text(
                "${model.time(e)} · ${kindLabel(e.kind)}",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (e.kind == LogKind.KILL_SWITCH || e.kind == LogKind.DISCONNECT) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary,
            )
            Text(e.text, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }

      Text(
          stringResource(R.string.connlog_privacy),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun kindLabel(k: LogKind): String =
    stringResource(
        when (k) {
          LogKind.INFO -> R.string.connlog_kind_info
          LogKind.CONNECT -> R.string.connlog_kind_connect
          LogKind.DISCONNECT -> R.string.connlog_kind_disconnect
          LogKind.EXIT_NODE -> R.string.connlog_kind_exit
          LogKind.PROXY -> R.string.connlog_kind_proxy
          LogKind.KILL_SWITCH -> R.string.connlog_kind_killswitch
        })

@Composable
private fun LogCard(content: @Composable () -> Unit) {
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
