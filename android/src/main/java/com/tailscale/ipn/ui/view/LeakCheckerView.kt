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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
  val headline by model.headline.collectAsState()
  val observer by model.observer.collectAsState()

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
        headline?.let {
          Text(it.second, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
              color = if (it.first == LeakSeverity.FAIL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        findings.forEach { FindingRow(it) }
        var zone by remember(observer) { mutableStateOf(observer.zone) }
        var endpoint by remember(observer) { mutableStateOf(observer.endpoint) }
        var expected by remember(observer) { mutableStateOf(observer.expected) }
        Text(stringResource(R.string.leakcheck_obs_heading), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.leakcheck_obs_body), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(zone, { zone = it }, label = { Text(stringResource(R.string.leakcheck_obs_zone)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(R.string.leakcheck_obs_endpoint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(expected, { expected = it }, label = { Text(stringResource(R.string.leakcheck_obs_expected)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { model.saveObserver(zone, endpoint, expected) }) { Text(stringResource(R.string.leakcheck_obs_save)) }
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
        LeakSeverity.INCONCLUSIVE -> MaterialTheme.colorScheme.onSurfaceVariant
      }
  val label =
      when (finding.severity) {
        LeakSeverity.OK -> stringResource(R.string.leakcheck_ok)
        LeakSeverity.WARN -> stringResource(R.string.leakcheck_warn)
        LeakSeverity.FAIL -> stringResource(R.string.leakcheck_fail)
        LeakSeverity.INCONCLUSIVE -> stringResource(R.string.leakcheck_inconclusive)
      }
  val evidence =
      when (finding.evidence) {
        com.tailscale.ipn.leakcheck.LeakEvidence.CONFIGURATION -> stringResource(R.string.leakcheck_ev_config)
        com.tailscale.ipn.leakcheck.LeakEvidence.OBSERVED -> stringResource(R.string.leakcheck_ev_observed)
        com.tailscale.ipn.leakcheck.LeakEvidence.VERIFIED -> stringResource(R.string.leakcheck_ev_verified)
      }
  Text(
      "$label [$evidence] · ${finding.detail}",
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
