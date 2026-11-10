// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.dns.DnsMatch
import com.tailscale.ipn.dns.DnsRule
import com.tailscale.ipn.dns.PerNetworkDns
import com.tailscale.ipn.ui.viewModel.PerNetworkDnsViewModel
import com.victorious.mesh.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerNetworkDnsView(onBack: BackNavigation, model: PerNetworkDnsViewModel = viewModel()) {
  val ctx = LocalContext.current
  val config by model.config.collectAsState()
  val message by model.message.collectAsState()
  val active by model.active.collectAsState()
  val network by model.network.collectAsState()
  val paused by model.paused.collectAsState()
  var hasLocation by remember { mutableStateOf(PerNetworkDns.hasLocationPermission(ctx)) }
  val askLocation =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = PerNetworkDns.hasLocationPermission(ctx)
        model.setEnabled(model.config.value.enabled) // re-check the Wi-Fi name
      }
  LaunchedEffect(Unit) { model.refresh() }

  var editingId by remember { mutableStateOf("") }
  var match by remember { mutableStateOf(DnsMatch.ANY_WIFI) }
  var ssid by remember { mutableStateOf("") }
  var servers by remember { mutableStateOf("") }
  val reset = {
    editingId = ""
    match = DnsMatch.ANY_WIFI
    ssid = ""
    servers = ""
  }

  Scaffold(topBar = { Header(R.string.pndns_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      DnsCard {
        Text(
            stringResource(R.string.pndns_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.pndns_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            R.string.pndns_enable,
            subtitle = stringResource(R.string.pndns_enable_subtitle),
            isOn = config.enabled,
            onToggle = { model.setEnabled(it) },
        )
        if (!model.meshDnsOn()) Warn(stringResource(R.string.pndns_warn_corpdns))
        if (model.serverOverrides()) Warn(stringResource(R.string.pndns_warn_override))
        if (config.enabled && paused) Warn(stringResource(R.string.pndns_paused_forced))
        if (config.enabled) {
          val n = network
          val where =
              when {
                n == null -> stringResource(R.string.pndns_net_unknown)
                n.wifi -> n.ssid?.let { stringResource(R.string.pndns_net_wifi_named, it) } ?: stringResource(R.string.pndns_net_wifi)
                n.cellular -> stringResource(R.string.pndns_net_cell)
                else -> stringResource(R.string.pndns_net_other)
              }
          Text(where, style = MaterialTheme.typography.bodySmall)
          Text(
              active?.let { stringResource(R.string.pndns_active, it.servers.joinToString(", ")) }
                  ?: stringResource(R.string.pndns_active_none),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      DnsCard {
        Text(stringResource(R.string.pndns_rules), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (config.rules.isEmpty()) Text(stringResource(R.string.pndns_empty), style = MaterialTheme.typography.bodySmall)
        config.rules.forEach { r ->
          Text(ruleLabel(r), fontWeight = FontWeight.Bold)
          Text(r.servers.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                  editingId = r.id
                  match = r.match
                  ssid = r.ssid
                  servers = r.servers.joinToString(", ")
                }) {
                  Text(stringResource(R.string.pndns_edit_rule))
                }
            TextButton(
                onClick = {
                  model.delete(r.id)
                  if (editingId == r.id) reset()
                }) {
                  Text(stringResource(R.string.pndns_delete), color = MaterialTheme.colorScheme.secondary)
                }
          }
        }
      }

      DnsCard {
        Text(
            stringResource(if (editingId.isEmpty()) R.string.pndns_add else R.string.pndns_edit),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          DnsMatch.entries.forEach { m -> FilterChip(selected = match == m, onClick = { match = m }, label = { Text(matchLabel(m)) }) }
        }
        if (match == DnsMatch.WIFI_SSID) {
          OutlinedTextField(ssid, { ssid = it.take(32) }, label = { Text(stringResource(R.string.pndns_ssid)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
          if (!hasLocation) {
            Warn(stringResource(R.string.pndns_location_needed))
            TextButton(
                onClick = {
                  askLocation.launch(
                      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) {
                  Text(stringResource(R.string.pndns_location_grant))
                }
          }
        }
        OutlinedTextField(servers, { servers = it }, label = { Text(stringResource(R.string.pndns_servers)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { if (model.save(DnsRule(editingId, match, ssid), servers)) reset() }) {
            Text(stringResource(R.string.pndns_save))
          }
          if (editingId.isNotEmpty()) TextButton(onClick = reset) { Text(stringResource(R.string.pndns_cancel)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
      }

      Text(
          stringResource(R.string.pndns_safety),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun Warn(text: String) {
  Text(text, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun matchLabel(m: DnsMatch): String =
    stringResource(
        when (m) {
          DnsMatch.WIFI_SSID -> R.string.pndns_match_ssid
          DnsMatch.ANY_WIFI -> R.string.pndns_match_wifi
          DnsMatch.CELLULAR -> R.string.pndns_match_cell
        })

@Composable
private fun ruleLabel(r: DnsRule): String =
    if (r.match == DnsMatch.WIFI_SSID) stringResource(R.string.pndns_net_wifi_named, r.ssid) else matchLabel(r.match)

@Composable
private fun DnsCard(content: @Composable () -> Unit) {
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
