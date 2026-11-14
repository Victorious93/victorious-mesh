// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.dns.DnsRoute
import com.tailscale.ipn.dns.DnsToolsPlanner
import com.tailscale.ipn.dns.DnsTransport
import com.tailscale.ipn.dns.ResolverProfile
import com.tailscale.ipn.ui.viewModel.DnsToolsViewModel
import com.victorious.mesh.R

private val TYPES = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "PTR", "SRV", "CAA")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DnsToolsView(onBack: BackNavigation, model: DnsToolsViewModel = viewModel()) {
  val ctx = LocalContext.current
  val profiles by model.profiles.collectAsState()
  val selectedId by model.selectedId.collectAsState()
  val output by model.output.collectAsState()
  val busy by model.busy.collectAsState()
  val message by model.message.collectAsState()
  val historyOn by model.historyOn.collectAsState()
  val cacheSize by model.cacheSize.collectAsState()
  val guardOn by model.guardOn.collectAsState()

  var name by remember { mutableStateOf("") }
  var type by remember { mutableStateOf("A") }
  var dnssec by remember { mutableStateOf(false) }
  var compareIds by remember { mutableStateOf(setOf<String>()) }
  var editing by remember { mutableStateOf<ResolverProfile?>(null) }
  var hosts by remember { mutableStateOf("") }
  var allow by remember { mutableStateOf("") }
  var block by remember { mutableStateOf("") }

  Scaffold(topBar = { Header(R.string.dnstools_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ToolsCard {
        Text(stringResource(R.string.dnstools_lookup), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.dnstools_body), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.dnstools_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
        }
        Text(stringResource(R.string.dnstools_ptr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Switch(dnssec, { dnssec = it })
          Text(stringResource(R.string.dnstools_dnssec), modifier = Modifier.padding(top = 12.dp))
        }
        Text(stringResource(R.string.dnstools_resolver), fontWeight = FontWeight.SemiBold)
        profiles.forEach { p ->
          FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = p.id == selectedId, onClick = { model.select(p.id) }, label = { Text(p.name) })
            Checkbox(checked = p.id in compareIds, onCheckedChange = { compareIds = if (it) compareIds + p.id else compareIds - p.id })
          }
          Text(DnsToolsPlanner.describe(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (!p.id.startsWith("preset-")) {
            FlowRow {
              TextButton(onClick = { editing = p }) { Text(stringResource(R.string.dnstools_edit)) }
              TextButton(onClick = { model.duplicateProfile(p.id) }) { Text(stringResource(R.string.dnstools_duplicate)) }
              TextButton(onClick = { model.deleteProfile(p.id) }) { Text(stringResource(R.string.dnstools_delete)) }
            }
          }
        }
        Text(stringResource(R.string.dnstools_compare_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !busy, onClick = { model.lookup(name, type, dnssec) }) { Text(stringResource(R.string.dnstools_run)) }
          TextButton(enabled = !busy, onClick = { model.compare(name, type, compareIds) }) { Text(stringResource(R.string.dnstools_compare)) }
          TextButton(enabled = !busy, onClick = { model.latency(name, type) }) { Text(stringResource(R.string.dnstools_latency)) }
          TextButton(onClick = { editing = ResolverProfile("", "", DnsTransport.DOT.wire, "", route = DnsRoute.DIRECT.wire) }) { Text(stringResource(R.string.dnstools_add)) }
        }
        if (busy) CircularProgressIndicator()
      }

      if (output.isNotBlank()) {
        ToolsCard {
          SelectionContainer {
            Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
          }
          TextButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DNS result", output))
          }) { Text(stringResource(R.string.dnstools_copy)) }
        }
      }

      ToolsCard {
        Text(stringResource(R.string.dnstools_guard), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.dnstools_guard_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Switch(guardOn, { model.setGuard(it) })
          Text(stringResource(R.string.dnstools_guard_switch), modifier = Modifier.padding(top = 12.dp))
        }
      }

      ToolsCard {
        Text(stringResource(R.string.dnstools_cache), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.dnstools_cache_body, cacheSize), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { model.clearCache() }) { Text(stringResource(R.string.dnstools_clear_cache)) }
          Switch(historyOn, { model.setHistory(it) })
          Text(stringResource(R.string.dnstools_history), modifier = Modifier.padding(top = 12.dp))
          TextButton(enabled = historyOn, onClick = { model.showHistory() }) { Text(stringResource(R.string.dnstools_history_show)) }
          TextButton(enabled = historyOn, onClick = { model.clearHistory() }) { Text(stringResource(R.string.dnstools_history_clear)) }
        }
      }

      ToolsCard {
        Text(stringResource(R.string.dnstools_local), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.dnstools_local_body), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(hosts, { hosts = it }, label = { Text(stringResource(R.string.dnstools_local_hosts)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(block, { block = it }, label = { Text(stringResource(R.string.dnstools_local_block)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(allow, { allow = it }, label = { Text(stringResource(R.string.dnstools_local_allow)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { model.applyLocalRules(hosts, allow, block) }) { Text(stringResource(R.string.dnstools_local_apply)) }
      }

      message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }
      Text(stringResource(R.string.dnstools_not_verified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }

  editing?.let { p -> ProfileEditor(p, onSave = { if (model.saveProfile(it)) editing = null }, onCancel = { editing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(initial: ResolverProfile, onSave: (ResolverProfile) -> Unit, onCancel: () -> Unit) {
  var name by remember { mutableStateOf(initial.name) }
  var transport by remember { mutableStateOf(initial.transport) }
  var server by remember { mutableStateOf(initial.server) }
  var tlsName by remember { mutableStateOf(initial.serverName) }
  var bootstrap by remember { mutableStateOf(initial.bootstrap.joinToString(", ")) }
  var route by remember { mutableStateOf(initial.route) }
  var priv by remember { mutableStateOf(initial.private) }
  androidx.compose.material3.AlertDialog(
      onDismissRequest = onCancel,
      title = { Text(stringResource(R.string.dnstools_add)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.dnstools_p_name)) }, singleLine = true)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DnsTransport.UDP, DnsTransport.TCP, DnsTransport.DOT, DnsTransport.DOH).forEach { t ->
              FilterChip(selected = transport == t.wire, onClick = { transport = t.wire }, label = { Text(t.name) })
            }
          }
          OutlinedTextField(server, { server = it }, label = { Text(stringResource(if (transport == DnsTransport.DOH.wire) R.string.dnstools_p_url else R.string.dnstools_p_server)) }, singleLine = true)
          if (transport == DnsTransport.DOT.wire || transport == DnsTransport.DOH.wire) {
            OutlinedTextField(tlsName, { tlsName = it }, label = { Text(stringResource(R.string.dnstools_p_tls)) }, singleLine = true)
          }
          OutlinedTextField(bootstrap, { bootstrap = it }, label = { Text(stringResource(R.string.dnstools_p_bootstrap)) }, singleLine = true)
          Text(stringResource(R.string.dnstools_p_route))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DnsRoute.entries.forEach { r -> FilterChip(selected = route == r.wire, onClick = { route = r.wire }, label = { Text(r.name.lowercase()) }) }
          }
          FlowRow {
            Checkbox(priv, { priv = it })
            Text(stringResource(R.string.dnstools_p_private), modifier = Modifier.padding(top = 12.dp))
          }
        }
      },
      confirmButton = {
        TextButton(onClick = {
          onSave(
              initial.copy(
                  name = name.trim(), transport = transport, server = server.trim(), serverName = tlsName.trim(),
                  bootstrap = bootstrap.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }, route = route, private = priv))
        }) { Text(stringResource(R.string.dnstools_save)) }
      },
      dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.dnstools_cancel)) } },
  )
}

@Composable
private fun ToolsCard(content: @Composable () -> Unit) {
  val brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
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
