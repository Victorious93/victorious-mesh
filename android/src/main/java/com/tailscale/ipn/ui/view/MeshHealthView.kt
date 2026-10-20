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
import com.tailscale.ipn.meshhealth.MeshHealthFinding
import com.tailscale.ipn.meshhealth.MeshHealthSeverity
import com.tailscale.ipn.ui.viewModel.MeshHealthViewModel
import com.victorious.mesh.R

@Composable
fun MeshHealthView(
    onBack: BackNavigation,
    model: MeshHealthViewModel = viewModel(),
) {
  val report by model.report.collectAsState()
  val checking by model.checking.collectAsState()

  Scaffold(topBar = { Header(R.string.meshhealth_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonMeshHealthCard {
        Text(
            stringResource(R.string.meshhealth_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.meshhealth_body), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { model.runCheck() }, enabled = !checking) {
          Text(
              stringResource(
                  if (checking) R.string.meshhealth_running else R.string.meshhealth_run
              )
          )
        }
        report?.findings?.forEach { FindingRow(it) }
        Text(
            stringResource(R.string.meshhealth_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun FindingRow(finding: MeshHealthFinding) {
  val color =
      when (finding.severity) {
        MeshHealthSeverity.OK -> MaterialTheme.colorScheme.primary
        MeshHealthSeverity.WARN -> MaterialTheme.colorScheme.tertiary
        MeshHealthSeverity.FAIL -> MaterialTheme.colorScheme.error
      }
  val label =
      when (finding.severity) {
        MeshHealthSeverity.OK -> stringResource(R.string.meshhealth_ok)
        MeshHealthSeverity.WARN -> stringResource(R.string.meshhealth_warn)
        MeshHealthSeverity.FAIL -> stringResource(R.string.meshhealth_fail)
      }
  Text(
      "$label · ${finding.detail}",
      style = MaterialTheme.typography.bodyMedium,
      color = color,
  )
}

@Composable
private fun NeonMeshHealthCard(content: @Composable () -> Unit) {
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
