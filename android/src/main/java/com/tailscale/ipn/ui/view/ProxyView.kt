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
import androidx.compose.material3.CircularProgressIndicator
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
import com.tailscale.ipn.proxy.OverlayBackend
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
  var upTlsName by remember(upstream.serverName) { mutableStateOf(upstream.serverName) }
  var upOutproxy by remember(upstream.outproxy) { mutableStateOf(upstream.outproxy) }
  var testTarget by remember { mutableStateOf("") }
  val testRunning by model.testRunning.collectAsState()
  val testReport by model.testReport.collectAsState()

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
        // Two rows so five choices fit on a phone.
        listOf(
                listOf(UpstreamKind.NONE, UpstreamKind.HTTP, UpstreamKind.HTTPS),
                listOf(UpstreamKind.SOCKS5, UpstreamKind.SAM),
            )
            .forEach { rowKinds ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKinds.forEach { kind ->
                  FilterChip(
                      selected = upstream.kind == kind,
                      onClick = {
                        // Changing the kind clears a backend that cannot go with it.
                        val keep =
                            when (kind) {
                              UpstreamKind.SOCKS5 -> upstream.backend.takeIf { it == OverlayBackend.TOR }
                              UpstreamKind.HTTP -> upstream.backend.takeIf { it == OverlayBackend.I2P_HTTP }
                              UpstreamKind.SAM -> OverlayBackend.I2P_SAM
                              else -> null
                            } ?: OverlayBackend.NONE
                        model.setUpstream(kind, upHost, upPort, upUser, upPass, upstream.forced, upstream.failClosed, upTlsName, keep, upOutproxy)
                      },
                      label = { Text(kindLabel(kind)) },
                  )
                }
              }
            }
        Text(kindHint(upstream.kind), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (upstream.kind == UpstreamKind.HTTPS) {
          OutlinedTextField(
              upTlsName,
              { upTlsName = it },
              label = { Text(stringResource(R.string.proxy_tls_name)) },
              supportingText = { Text(stringResource(R.string.proxy_tls_name_help)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
        }
        val backends =
            when (upstream.kind) {
              UpstreamKind.SOCKS5 -> listOf(OverlayBackend.NONE, OverlayBackend.TOR)
              UpstreamKind.HTTP -> listOf(OverlayBackend.NONE, OverlayBackend.I2P_HTTP)
              else -> emptyList()
            }
        if (backends.isNotEmpty()) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            backends.forEach { b ->
              FilterChip(
                  selected = upstream.backend == b,
                  onClick = {
                    model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, upstream.forced, upstream.failClosed, upTlsName, b, upOutproxy)
                  },
                  label = { Text(backendLabel(b)) },
              )
            }
          }
        }
        if (upstream.backend != OverlayBackend.NONE) {
          Text(
              stringResource(R.string.proxy_backend_never_direct),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
          )
        }
        if (upstream.backend == OverlayBackend.I2P_SAM || upstream.backend == OverlayBackend.I2P_HTTP) {
          OutlinedTextField(
              upOutproxy,
              { upOutproxy = it },
              label = { Text(stringResource(R.string.proxy_outproxy)) },
              supportingText = { Text(stringResource(R.string.proxy_outproxy_help)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
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
              // Turning forcing on re-arms fail-closed (the safe default).
              model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, forced = on, failClosed = on || upstream.failClosed)
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
        if (upstream.forced && !upstream.failClosed) {
          Text(
              stringResource(R.string.proxy_fail_open_warning),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(vertical = 4.dp),
          )
        }
        ForcedPathStatus(dnsState, ipv6State)
        TextButton(
            onClick = {
              model.setUpstream(upstream.kind, upHost, upPort, upUser, upPass, upstream.forced, upstream.failClosed, upTlsName, upstream.backend, upOutproxy)
            }) {
              Text(stringResource(R.string.proxy_save_upstream))
            }
        // Connection test: reachability, TLS, authentication, negotiation, a real request.
        OutlinedTextField(
            testTarget,
            { testTarget = it },
            label = { Text(stringResource(R.string.proxy_test_target)) },
            supportingText = { Text(stringResource(R.string.proxy_test_target_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(enabled = !testRunning && upstream.kind != UpstreamKind.NONE, onClick = { model.runTest(testTarget) }) {
            Text(stringResource(R.string.proxy_test_run))
          }
          if (testRunning) CircularProgressIndicator(Modifier.padding(8.dp))
        }
        testReport?.let { TestReportView(it) }
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
      UpstreamKind.HTTPS -> stringResource(R.string.proxy_kind_https)
      UpstreamKind.SOCKS5 -> stringResource(R.string.proxy_kind_socks5)
      UpstreamKind.SAM -> stringResource(R.string.proxy_kind_sam)
    }

@Composable
private fun kindHint(kind: UpstreamKind): String =
    when (kind) {
      UpstreamKind.NONE -> ""
      UpstreamKind.HTTP -> stringResource(R.string.proxy_hint_http)
      UpstreamKind.HTTPS -> stringResource(R.string.proxy_hint_https)
      UpstreamKind.SOCKS5 -> stringResource(R.string.proxy_hint_socks5)
      UpstreamKind.SAM -> stringResource(R.string.proxy_hint_sam)
    }

@Composable
private fun backendLabel(b: OverlayBackend): String =
    when (b) {
      OverlayBackend.NONE -> stringResource(R.string.proxy_backend_plain)
      OverlayBackend.TOR -> stringResource(R.string.proxy_backend_tor)
      OverlayBackend.I2P_SAM -> stringResource(R.string.proxy_backend_i2p_sam)
      OverlayBackend.I2P_HTTP -> stringResource(R.string.proxy_backend_i2p_http)
    }

@Composable
private fun TestReportView(report: com.tailscale.ipn.ui.viewModel.ProxyTestReport) {
  Text(
      stringResource(R.string.proxy_test_result),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
  )
  report.error?.let {
    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    return
  }
  report.steps.forEach { s ->
    val mark = if (s.skipped) "–" else if (s.ok) "✓" else "✗"
    Text(
        "$mark ${s.stage}: ${s.detail}" + if (!s.skipped && s.ms > 0) " (${s.ms} ms)" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (!s.skipped && !s.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
  }
  Text(
      if (report.passed) stringResource(R.string.proxy_test_ok) else stringResource(R.string.proxy_test_failed_at, report.verdict),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
  )
  report.isTor?.let {
    Text(
        if (it) stringResource(R.string.proxy_test_is_tor) else stringResource(R.string.proxy_test_not_tor),
        style = MaterialTheme.typography.bodySmall,
    )
  }
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
