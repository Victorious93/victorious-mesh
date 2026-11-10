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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.viewModel.WakeOnLanViewModel
import com.tailscale.ipn.wol.WakeOnLanPlanner
import com.tailscale.ipn.wol.WolDevice
import com.tailscale.ipn.wol.WolRoute
import com.victorious.mesh.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeOnLanView(onBack: BackNavigation, model: WakeOnLanViewModel = viewModel()) {
  val devices by model.devices.collectAsState()
  val message by model.message.collectAsState()
  val peers = remember { model.peers() }

  var editingId by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var mac by remember { mutableStateOf("") }
  var address by remember { mutableStateOf(WakeOnLanPlanner.LIMITED_BROADCAST) }
  var portText by remember { mutableStateOf("9") }
  var route by remember { mutableStateOf(WolRoute.LAN) }
  var peerId by remember { mutableStateOf("") }
  val reset = {
    editingId = ""
    name = ""
    mac = ""
    address = WakeOnLanPlanner.LIMITED_BROADCAST
    portText = "9"
    route = WolRoute.LAN
    peerId = ""
  }

  Scaffold(topBar = { Header(R.string.wol_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      WolCard {
        Text(
            stringResource(R.string.wol_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.wol_body), style = MaterialTheme.typography.bodyMedium)
        if (devices.isEmpty()) Text(stringResource(R.string.wol_empty), style = MaterialTheme.typography.bodySmall)
        devices.forEach { d ->
          Text(d.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
          Text(
              "${d.mac} · ${routeLabel(d.route)}" +
                  (if (d.route == WolRoute.PEER_COMMAND) " · ${model.peerName(d.peerNodeId)}" else " · ${d.address}:${d.port}"),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.wake(d) }) { Text(stringResource(R.string.wol_wake)) }
            TextButton(
                onClick = {
                  editingId = d.id
                  name = d.name
                  mac = d.mac
                  address = d.address
                  portText = d.port.toString()
                  route = d.route
                  peerId = d.peerNodeId
                }) {
                  Text(stringResource(R.string.wol_edit))
                }
            TextButton(
                onClick = {
                  model.delete(d.id)
                  if (editingId == d.id) reset()
                }) {
                  Text(stringResource(R.string.wol_delete), color = MaterialTheme.colorScheme.secondary)
                }
          }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
      }

      WolCard {
        Text(
            if (editingId.isEmpty()) stringResource(R.string.wol_add) else stringResource(R.string.wol_editing, name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.wol_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(mac, { mac = it }, label = { Text(stringResource(R.string.wol_mac)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          WolRoute.entries.forEach { r ->
            FilterChip(selected = route == r, onClick = { route = r }, label = { Text(routeLabel(r)) })
          }
        }
        Text(
            stringResource(
                when (route) {
                  WolRoute.LAN -> R.string.wol_route_lan_help
                  WolRoute.MESH_SUBNET -> R.string.wol_route_mesh_help
                  WolRoute.PEER_COMMAND -> R.string.wol_route_peer_help
                }),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(address, { address = it.trim() }, label = { Text(stringResource(R.string.wol_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(portText, { portText = it.filter { c -> c.isDigit() }.take(5) }, label = { Text(stringResource(R.string.proxy_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (route == WolRoute.PEER_COMMAND) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            peers.forEach { p ->
              FilterChip(selected = peerId == p.id, onClick = { peerId = p.id }, label = { Text(p.name) })
            }
          }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(
              onClick = {
                val ok =
                    model.save(
                        WolDevice(editingId, name, mac, address, portText.toIntOrNull() ?: 0, route, peerId))
                if (ok) reset()
              }) {
                Text(stringResource(R.string.wol_save))
              }
          if (editingId.isNotEmpty()) TextButton(onClick = reset) { Text(stringResource(R.string.wol_cancel)) }
        }
      }

      Text(
          stringResource(R.string.wol_not_device_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun routeLabel(r: WolRoute): String =
    stringResource(
        when (r) {
          WolRoute.LAN -> R.string.wol_route_lan
          WolRoute.MESH_SUBNET -> R.string.wol_route_mesh
          WolRoute.PEER_COMMAND -> R.string.wol_route_peer
        })

@Composable
private fun WolCard(content: @Composable () -> Unit) {
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
