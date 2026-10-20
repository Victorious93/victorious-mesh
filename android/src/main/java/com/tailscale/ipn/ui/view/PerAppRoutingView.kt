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
import com.tailscale.ipn.routing.AppRouteKind
import com.tailscale.ipn.ui.viewModel.PerAppRoutingViewModel
import com.victorious.mesh.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerAppRoutingView(onBack: BackNavigation, model: PerAppRoutingViewModel = viewModel()) {
  val apps by model.installedApps.collectAsState()
  val endpoint by model.endpoint.collectAsState()
  val sighting by model.sighting.collectAsState()
  val message by model.message.collectAsState()
  val checking by model.checking.collectAsState()
  var endpointText by remember(endpoint) { mutableStateOf(endpoint) }
  val exits = remember { model.exitChoices() }

  Scaffold(topBar = { Header(R.string.perapp_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonCard {
        Text(
            stringResource(R.string.perapp_limits),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            endpointText,
            {
              endpointText = it
              model.setEndpoint(it)
            },
            label = { Text(stringResource(R.string.perapp_endpoint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { model.checkSighting() }, enabled = !checking) {
          Text(stringResource(R.string.perapp_check))
        }
        sighting?.let { seen ->
          Text(
              stringResource(R.string.perapp_sighting, seen.ip ?: "—", seen.country ?: "—"),
              style = MaterialTheme.typography.bodyMedium,
          )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
      }

      apps.forEach { app ->
        val route = model.routeFor(app.packageName)
        NeonCard {
          Text(app.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          Text(app.packageName, style = MaterialTheme.typography.bodySmall)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppRouteKind.entries.forEach { kind ->
              FilterChip(
                  selected = route.kind == kind,
                  onClick = { model.setKind(app.packageName, kind) },
                  label = { Text(kindLabel(kind)) },
              )
            }
          }
          if (route.kind == AppRouteKind.EXIT_NODE && exits.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              exits.forEach { exit ->
                FilterChip(
                    selected = route.exitNodeId == exit.id,
                    onClick = { model.setExitNode(app.packageName, exit.id) },
                    label = { Text(exit.label) },
                )
              }
            }
          }
          Text(model.appliedReason(app.packageName), style = MaterialTheme.typography.bodySmall)
        }
      }

      Text(
          stringResource(R.string.perapp_not_device_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun kindLabel(kind: AppRouteKind): String =
    when (kind) {
      AppRouteKind.DIRECT -> stringResource(R.string.perapp_kind_direct)
      AppRouteKind.MESH -> stringResource(R.string.perapp_kind_mesh)
      AppRouteKind.EXIT_NODE -> stringResource(R.string.perapp_kind_exit)
      AppRouteKind.UPSTREAM_PROXY -> stringResource(R.string.perapp_kind_upstream)
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
