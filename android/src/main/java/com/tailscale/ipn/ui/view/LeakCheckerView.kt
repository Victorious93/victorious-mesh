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
import com.tailscale.ipn.leakcheck.LeakFinding
import com.tailscale.ipn.leakcheck.LeakSeverity
import com.tailscale.ipn.ui.viewModel.LeakCheckerViewModel
import com.victorious.mesh.R

@Composable
fun LeakCheckerView(
    onBack: BackNavigation,
    model: LeakCheckerViewModel = viewModel(),
) {
  val findings by model.findings.collectAsState()
  val checking by model.checking.collectAsState()

  Scaffold(topBar = { Header(R.string.leakcheck_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonLeakCard {
        Text(
            stringResource(R.string.leakcheck_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.leakcheck_body), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { model.runCheck() }, enabled = !checking) {
          Text(
              stringResource(
                  if (checking) R.string.leakcheck_running else R.string.leakcheck_run
              )
          )
        }
        findings.forEach { FindingRow(it) }
        Text(
            stringResource(R.string.leakcheck_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun FindingRow(finding: LeakFinding) {
  val color =
      when (finding.severity) {
        LeakSeverity.OK -> MaterialTheme.colorScheme.primary
        LeakSeverity.WARN -> MaterialTheme.colorScheme.tertiary
        LeakSeverity.FAIL -> MaterialTheme.colorScheme.error
      }
  val label =
      when (finding.severity) {
        LeakSeverity.OK -> stringResource(R.string.leakcheck_ok)
        LeakSeverity.WARN -> stringResource(R.string.leakcheck_warn)
        LeakSeverity.FAIL -> stringResource(R.string.leakcheck_fail)
      }
  Text(
      "$label · ${finding.detail}",
      style = MaterialTheme.typography.bodyMedium,
      color = color,
  )
}

@Composable
private fun NeonLeakCard(content: @Composable () -> Unit) {
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
