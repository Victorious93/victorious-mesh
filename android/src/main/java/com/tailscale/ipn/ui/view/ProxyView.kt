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
import com.tailscale.ipn.proxy.ForcedDNSStatus
import com.tailscale.ipn.proxy.ForcedIPv6Status
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.ui.viewModel.ProxyViewModel
import com.victorious.mesh.R

@Composable
fun ProxyView(onBack: BackNavigation, model: ProxyViewModel = viewModel()) {
  val mesh by model.mesh.collectAsState()
  val upstream by model.upstream.collectAsState()
  val running by model.running.collectAsState()
  val message by model.message.collectAsState()
  val dnsState by model.dnsState.collectAsState()
  val ipv6State by model.ipv6State.collectAsState()
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
                onClick = {
                  model.setUpstream(kind, upHost, upPort, upUser, upPass, upstream.forced, upstream.failClosed)
                },
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
        Setting.Switch(
            titleRes = R.string.proxy_forced_enable,
            subtitle = stringResource(R.string.proxy_forced_body),
            isOn = upstream.forced,
            onToggle = { on ->
              model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, forced = on, failClosed = upstream.failClosed)
            },
        )
        Setting.Switch(
            titleRes = R.string.proxy_fail_closed,
            subtitle = stringResource(R.string.proxy_fail_closed_body),
            isOn = upstream.failClosed,
            onToggle = { on ->
              model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, forced = upstream.forced, failClosed = on)
            },
        )
        ForcedPathStatus(dnsState, ipv6State)
        TextButton(onClick = { model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, upstream.forced, upstream.failClosed) }) {
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
private fun ForcedPathStatus(dns: ForcedDNSStatus, ipv6: ForcedIPv6Status) {
  Text(
      stringResource(R.string.proxy_path_heading),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
  )
  Text(
      stringResource(R.string.proxy_dns_heading),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
  )
  Text(dnsLabel(dns), style = MaterialTheme.typography.bodyMedium)
  Text(
      stringResource(R.string.proxy_ipv6_heading),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
  )
  Text(ipv6Label(ipv6), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun dnsLabel(state: ForcedDNSStatus): String =
    when (state) {
      ForcedDNSStatus.OFF -> stringResource(R.string.proxy_dns_off)
      ForcedDNSStatus.PENDING -> stringResource(R.string.proxy_dns_pending)
      ForcedDNSStatus.UDP -> stringResource(R.string.proxy_dns_udp)
      ForcedDNSStatus.TCP -> stringResource(R.string.proxy_dns_tcp)
      ForcedDNSStatus.BLOCKED -> stringResource(R.string.proxy_dns_blocked)
    }

@Composable
private fun ipv6Label(state: ForcedIPv6Status): String =
    when (state) {
      ForcedIPv6Status.OFF -> stringResource(R.string.proxy_ipv6_off)
      ForcedIPv6Status.UNKNOWN -> stringResource(R.string.proxy_ipv6_unknown)
      ForcedIPv6Status.VIA_PROXY -> stringResource(R.string.proxy_ipv6_via)
      ForcedIPv6Status.BLOCKED -> stringResource(R.string.proxy_ipv6_blocked)
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
