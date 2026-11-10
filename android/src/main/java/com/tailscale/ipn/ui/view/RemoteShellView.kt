// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

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
import com.tailscale.ipn.shell.ShellClient
import com.tailscale.ipn.shell.ShellTarget
import com.tailscale.ipn.shell.TERMUX_SSHD_PORT
import com.tailscale.ipn.ui.viewModel.RemoteShellViewModel
import com.victorious.mesh.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteShellView(onBack: BackNavigation, model: RemoteShellViewModel = viewModel()) {
  val targets by model.targets.collectAsState()
  val message by model.message.collectAsState()
  val context = LocalContext.current
  val nodes = remember { model.nodes() }

  var editingId by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var nodeId by remember { mutableStateOf("") }
  var host by remember { mutableStateOf("") }
  var user by remember { mutableStateOf("") }
  var portText by remember { mutableStateOf("22") }
  var client by remember { mutableStateOf(ShellClient.SSH_APP) }
  val reset = {
    editingId = ""
    name = ""
    nodeId = ""
    host = ""
    user = ""
    portText = "22"
    client = ShellClient.SSH_APP
  }
  val draft =
      ShellTarget(editingId, name, nodeId, host, user, portText.toIntOrNull() ?: 0, client)

  Scaffold(topBar = { Header(R.string.shell_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ShellCard {
        Text(
            stringResource(R.string.shell_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.shell_body), style = MaterialTheme.typography.bodyMedium)
        if (targets.isEmpty()) {
          Text(stringResource(R.string.shell_empty), style = MaterialTheme.typography.bodySmall)
        }
        targets.forEach { t ->
          Text(t.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
          Text(
              model.commandFor(t) ?: (model.problem(t) ?: ""),
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              color =
                  if (model.commandFor(t) == null) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.open(context, t) }) { Text(stringResource(R.string.shell_open)) }
            TextButton(
                onClick = {
                  editingId = t.id
                  name = t.name
                  nodeId = t.nodeId
                  host = t.host
                  user = t.user
                  portText = t.port.toString()
                  client = t.client
                }) {
                  Text(stringResource(R.string.shell_edit))
                }
            TextButton(
                onClick = {
                  model.delete(t.id)
                  if (editingId == t.id) reset()
                }) {
                  Text(stringResource(R.string.shell_delete), color = MaterialTheme.colorScheme.secondary)
                }
          }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
      }

      ShellCard {
        Text(
            if (editingId.isEmpty()) stringResource(R.string.shell_add) else stringResource(R.string.shell_editing, name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            name, { name = it }, label = { Text(stringResource(R.string.shell_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.shell_pick_node), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          nodes.forEach { n ->
            FilterChip(
                selected = nodeId == n.id,
                onClick = { nodeId = if (nodeId == n.id) "" else n.id },
                label = { Text(if (n.online) n.name else "${n.name} (offline)") },
            )
          }
        }
        OutlinedTextField(
            host, { host = it.trim() }, label = { Text(stringResource(R.string.shell_host)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            user, { user = it.trim() }, label = { Text(stringResource(R.string.shell_user)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            portText, { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.proxy_port)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = portText == "22", onClick = { portText = "22" }, label = { Text("22") })
          FilterChip(
              selected = portText == TERMUX_SSHD_PORT.toString(),
              onClick = { portText = TERMUX_SSHD_PORT.toString() },
              label = { Text(stringResource(R.string.shell_termux_port)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ShellClient.entries.forEach { c ->
            FilterChip(
                selected = client == c,
                onClick = { client = c },
                label = {
                  Text(
                      stringResource(
                          when (c) {
                            ShellClient.SSH_APP -> R.string.shell_client_app
                            ShellClient.TERMUX -> R.string.shell_client_termux
                            ShellClient.COMMAND -> R.string.shell_client_command
                          }))
                },
            )
          }
        }
        Text(
            model.commandFor(draft) ?: (model.problem(draft) ?: ""),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { if (model.save(draft)) reset() }) { Text(stringResource(R.string.shell_save)) }
          if (editingId.isNotEmpty()) {
            TextButton(onClick = reset) { Text(stringResource(R.string.shell_cancel)) }
          }
        }
      }

      Text(
          stringResource(R.string.shell_not_device_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ShellCard(content: @Composable () -> Unit) {
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
