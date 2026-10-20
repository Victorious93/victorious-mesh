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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.ui.viewModel.ProxyViewModel
import com.victorious.mesh.R

@Composable
fun ProxyView(onBack: BackNavigation, model: ProxyViewModel = viewModel()) {
  val mesh by model.mesh.collectAsState()
  val upstream by model.upstream.collectAsState()
  val running by model.running.collectAsState()
  val message by model.message.collectAsState()
  var portText by remember(mesh.port) { mutableStateOf(mesh.port.toString()) }
  var user by remember(mesh.username) { mutableStateOf(mesh.username) }
  var pass by remember { mutableStateOf(mesh.password) }
  var upHost by remember(upstream.host) { mutableStateOf(upstream.host) }
  var upPort by remember(upstream.port) { mutableStateOf(upstream.port.toString()) }
  var upUser by remember(upstream.username) { mutableStateOf(upstream.username) }
  var upPass by remember { mutableStateOf(upstream.password) }

  Scaffold(topBar = { Header(R.string.proxy_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonCard {
        Text(
            stringResource(R.string.proxy_share_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.proxy_share_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            titleRes = R.string.proxy_share_enable,
            subtitle =
                if (running) stringResource(R.string.proxy_share_on, model.meshBindLabel(), mesh.port)
                else stringResource(R.string.proxy_share_off),
            isOn = mesh.enabled,
            onToggle = { model.setMeshEnabled(it) },
        )
        OutlinedTextField(
            portText,
            {
              portText = it
              if (it.toIntOrNull() != null) model.setMeshPort(it)
            },
            label = { Text(stringResource(R.string.proxy_port)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            user,
            { user = it },
            label = { Text(stringResource(R.string.proxy_username_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            pass,
            { pass = it },
            label = { Text(stringResource(R.string.proxy_password_optional)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { model.setMeshAuth(user, pass) }) {
          Text(stringResource(R.string.proxy_save_auth))
        }
      }

      NeonCard {
        Text(
            stringResource(R.string.proxy_upstream_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.proxy_upstream_body), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          UpstreamKind.entries.forEach { kind ->
            FilterChip(
                selected = upstream.kind == kind,
                onClick = { model.setUpstream(kind, upHost, upPort, upUser, upPass) },
                label = { Text(kindLabel(kind)) },
            )
          }
        }
        OutlinedTextField(
            upHost,
            { upHost = it },
            label = { Text(stringResource(R.string.proxy_upstream_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            upPort,
            { upPort = it },
            label = { Text(stringResource(R.string.proxy_port)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            upUser,
            { upUser = it },
            label = { Text(stringResource(R.string.proxy_username_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            upPass,
            { upPass = it },
            label = { Text(stringResource(R.string.proxy_password_optional)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass) }) {
          Text(stringResource(R.string.proxy_save_upstream))
        }
      }

      message?.let {
        Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
      }
      Text(
          stringResource(R.string.proxy_not_device_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun kindLabel(kind: UpstreamKind): String =
    when (kind) {
      UpstreamKind.NONE -> stringResource(R.string.proxy_kind_none)
      UpstreamKind.HTTP -> stringResource(R.string.proxy_kind_http)
      UpstreamKind.SOCKS5 -> stringResource(R.string.proxy_kind_socks5)
    }

@Composable
private fun NeonCard(content: @Composable () -> Unit) {
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
