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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.proxy.DEFAULT_SAVED_PROXY_PORT
import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.routing.AppRouteKind
import com.tailscale.ipn.routing.PerAppAction
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
  val routes by model.routes.collectAsState()
  val enforce by model.enforce.collectAsState()
  val failClosed by model.failClosed.collectAsState()
  val saved by model.savedProxies.collectAsState()
  val proxyMessage by model.proxyMessage.collectAsState()
  val unknownOwners by model.unknownOwners.collectAsState()
  // Read so the app cards recompose when choices change.
  val choiceKey = Triple(routes, enforce, failClosed) to saved

  Scaffold(topBar = { Header(R.string.perapp_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      NeonCard {
        Text(
            stringResource(R.string.perapp_enforce_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.perapp_enforce_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            titleRes = R.string.perapp_enforce_switch,
            subtitle =
                if (!model.supported) stringResource(R.string.perapp_enforce_needs_q)
                else if (enforce) stringResource(R.string.perapp_enforce_on)
                else stringResource(R.string.perapp_enforce_off),
            isOn = enforce,
            enabled = model.supported || enforce,
            onToggle = { model.setEnforce(it) },
        )
        Setting.Switch(
            titleRes = R.string.perapp_failclosed_switch,
            subtitle =
                if (failClosed) stringResource(R.string.perapp_failclosed_on)
                else stringResource(R.string.perapp_failclosed_off),
            isOn = failClosed,
            onToggle = { model.setFailClosed(it) },
        )
        if (!failClosed) {
          Text(
              stringResource(R.string.perapp_failclosed_warning),
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
          )
        }
        if (enforce && unknownOwners > 0) {
          Text(
              stringResource(R.string.perapp_unknown_owners, unknownOwners),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      SavedProxiesCard(
          saved = saved,
          message = proxyMessage,
          onSave = { model.saveProxy(it) == null },
          onDelete = { model.deleteProxy(it) },
          onDuplicate = { model.duplicateProxy(it) },
          onParseUri = { model.parseProxyUri(it) },
      )

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
        val route = routes.firstOrNull { it.pkg == app.packageName } ?: model.routeFor(app.packageName)
        val resolved = remember(choiceKey, app.packageName) { model.resolved(app.packageName) }
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
          if (route.kind == AppRouteKind.EXIT_NODE) {
            if (exits.isEmpty()) {
              Text(stringResource(R.string.perapp_no_exit_nodes), style = MaterialTheme.typography.bodySmall)
            }
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
          if (route.kind == AppRouteKind.UPSTREAM_PROXY) {
            val choices = remember(saved) { model.proxyChoices() }
            if (choices.isEmpty()) {
              Text(stringResource(R.string.perapp_no_proxies), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              choices.forEach { p ->
                FilterChip(
                    selected = route.proxyId.ifEmpty { com.tailscale.ipn.proxy.MAIN_UPSTREAM_PROXY_ID } == p.id,
                    onClick = { model.setProxy(app.packageName, p.id) },
                    label = { Text(p.name) },
                )
              }
            }
          }
          Text(
              resolved.reason,
              style = MaterialTheme.typography.bodySmall,
              color =
                  when {
                    resolved.action == PerAppAction.BLOCK -> MaterialTheme.colorScheme.error
                    resolved.warning -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                  },
          )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedProxiesCard(
    saved: List<SavedProxy>,
    message: String?,
    onSave: (SavedProxy) -> Boolean,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onParseUri: (String) -> SavedProxy?,
) {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  var tlsName by remember { mutableStateOf("") }
  var backend by remember { mutableStateOf(com.tailscale.ipn.proxy.OverlayBackend.NONE) }
  var outproxy by remember { mutableStateOf("") }
  var uri by remember { mutableStateOf("") }
  var editingId by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var kind by remember { mutableStateOf(UpstreamKind.SOCKS5) }
  var host by remember { mutableStateOf("") }
  var portText by remember { mutableStateOf(DEFAULT_SAVED_PROXY_PORT.toString()) }
  var user by remember { mutableStateOf("") }
  var pass by remember { mutableStateOf("") }
  val reset = {
    editingId = ""
    name = ""
    kind = UpstreamKind.SOCKS5
    host = ""
    portText = DEFAULT_SAVED_PROXY_PORT.toString()
    user = ""
    pass = ""
    tlsName = ""
    backend = com.tailscale.ipn.proxy.OverlayBackend.NONE
    outproxy = ""
    uri = ""
  }

  NeonCard {
    Text(
        stringResource(R.string.perapp_proxies_heading),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(stringResource(R.string.perapp_proxies_body), style = MaterialTheme.typography.bodyMedium)
    saved.forEach { p ->
      Text(
          stringResource(R.string.perapp_proxy_row, p.name, p.kind.name, p.host, p.port),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
              editingId = p.id
              name = p.name
              kind = p.kind
              host = p.host
              portText = p.port.toString()
              user = p.username
              pass = p.password
              tlsName = p.serverName
              backend = p.backend
              outproxy = p.outproxy
            }) {
              Text(stringResource(R.string.perapp_proxy_edit))
            }
        TextButton(onClick = { onDuplicate(p.id) }) { Text(stringResource(R.string.perapp_proxy_duplicate)) }
        TextButton(
            onClick = {
              // Never includes the password or the user name.
              (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                  .setPrimaryClip(android.content.ClipData.newPlainText("proxy", p.redactedExport()))
            }) {
              Text(stringResource(R.string.perapp_proxy_copy_redacted))
            }
        TextButton(
            onClick = {
              onDelete(p.id)
              if (editingId == p.id) reset()
            }) {
              Text(stringResource(R.string.perapp_proxy_delete), color = MaterialTheme.colorScheme.secondary)
            }
      }
    }
    Text(
        if (editingId.isEmpty()) stringResource(R.string.perapp_proxy_add)
        else stringResource(R.string.perapp_proxy_editing, name),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    OutlinedTextField(
        name,
        { name = it },
        label = { Text(stringResource(R.string.perapp_proxy_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(UpstreamKind.SOCKS5, UpstreamKind.HTTP, UpstreamKind.HTTPS, UpstreamKind.SAM).forEach { k ->
        FilterChip(
            selected = kind == k,
            onClick = {
              kind = k
              // A backend that cannot go with the new kind is cleared, never kept silently.
              backend =
                  when {
                    k == UpstreamKind.SAM -> com.tailscale.ipn.proxy.OverlayBackend.I2P_SAM
                    k == UpstreamKind.SOCKS5 && backend == com.tailscale.ipn.proxy.OverlayBackend.TOR -> backend
                    k == UpstreamKind.HTTP && backend == com.tailscale.ipn.proxy.OverlayBackend.I2P_HTTP -> backend
                    else -> com.tailscale.ipn.proxy.OverlayBackend.NONE
                  }
              if (k != UpstreamKind.HTTPS) tlsName = ""
            },
            label = {
              Text(
                  when (k) {
                    UpstreamKind.HTTP -> "HTTP CONNECT"
                    UpstreamKind.HTTPS -> "HTTPS CONNECT (TLS)"
                    UpstreamKind.SAM -> "I2P SAM"
                    else -> "SOCKS5"
                  })
            },
        )
      }
    }
    if (kind == UpstreamKind.SOCKS5 || kind == UpstreamKind.HTTP) {
      val special = if (kind == UpstreamKind.SOCKS5) com.tailscale.ipn.proxy.OverlayBackend.TOR else com.tailscale.ipn.proxy.OverlayBackend.I2P_HTTP
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = backend == com.tailscale.ipn.proxy.OverlayBackend.NONE, onClick = { backend = com.tailscale.ipn.proxy.OverlayBackend.NONE }, label = { Text(stringResource(R.string.proxy_backend_plain)) })
        FilterChip(selected = backend == special, onClick = { backend = special }, label = { Text(if (special == com.tailscale.ipn.proxy.OverlayBackend.TOR) stringResource(R.string.proxy_backend_tor) else stringResource(R.string.proxy_backend_i2p_http)) })
      }
    }
    if (kind == UpstreamKind.HTTPS) {
      OutlinedTextField(tlsName, { tlsName = it }, label = { Text(stringResource(R.string.proxy_tls_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (backend == com.tailscale.ipn.proxy.OverlayBackend.I2P_SAM || backend == com.tailscale.ipn.proxy.OverlayBackend.I2P_HTTP) {
      OutlinedTextField(outproxy, { outproxy = it }, label = { Text(stringResource(R.string.proxy_outproxy)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    // Import from a URI such as socks5://user:pass@[2001:db8::1]:1080 or https://proxy.example:443
    OutlinedTextField(uri, { uri = it }, label = { Text(stringResource(R.string.perapp_proxy_uri)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(
        enabled = uri.isNotBlank(),
        onClick = {
          onParseUri(uri.trim())?.let {
            kind = it.kind
            host = it.host
            portText = it.port.toString()
            user = it.username
            pass = it.password
            backend = it.backend
            if (name.isBlank()) name = it.host
            uri = "" // do not leave the pasted credentials sitting in the field
          }
        }) {
          Text(stringResource(R.string.perapp_proxy_uri_import))
        }
    OutlinedTextField(
        host,
        { host = it },
        label = { Text(stringResource(R.string.perapp_proxy_host)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        portText,
        { portText = it.filter { c -> c.isDigit() }.take(5) },
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(
          onClick = {
            val ok =
                onSave(
                    SavedProxy(
                        id = editingId,
                        name = name,
                        kind = kind,
                        host = host,
                        port = portText.toIntOrNull() ?: 0,
                        username = user,
                        password = pass,
                        serverName = tlsName,
                        backend = backend,
                        outproxy = outproxy,
                    ))
            if (ok) reset()
          }) {
            Text(stringResource(R.string.perapp_proxy_save))
          }
      if (editingId.isNotEmpty()) {
        TextButton(onClick = reset) { Text(stringResource(R.string.perapp_proxy_cancel)) }
      }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
  }
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
