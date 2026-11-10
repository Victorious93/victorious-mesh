// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.viewModel.MeshShareViewModel
import com.victorious.mesh.R

@Composable
fun MeshShareView(onBack: BackNavigation, model: MeshShareViewModel = viewModel()) {
  val cfg by model.config.collectAsState()
  val running by model.running.collectAsState()
  val files by model.files.collectAsState()
  val message by model.message.collectAsState()
  val busy by model.busy.collectAsState()
  var portText by remember(cfg.port) { mutableStateOf(cfg.port.toString()) }
  var showPassword by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val context = LocalContext.current
  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.addFiles(uris)
      }
  val url = model.shareUrl()

  Scaffold(topBar = { Header(R.string.meshshare_title, onBack = onBack) }) { inner ->
    Column(
        Modifier.padding(inner).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ShareCard {
        Text(
            stringResource(R.string.meshshare_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.meshshare_body), style = MaterialTheme.typography.bodyMedium)
        Setting.Switch(
            titleRes = R.string.meshshare_enable,
            subtitle =
                if (running && url != null) stringResource(R.string.meshshare_on, url)
                else stringResource(R.string.meshshare_off),
            isOn = cfg.enabled,
            onToggle = { model.setEnabled(it) },
        )
        OutlinedTextField(
            portText,
            { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text(stringResource(R.string.proxy_port)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { model.setPort(portText) }) { Text(stringResource(R.string.meshshare_save_port)) }
        Text(
            stringResource(
                R.string.meshshare_password, if (showPassword) cfg.password else "••••••••••••"),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { showPassword = !showPassword }) {
          Text(stringResource(if (showPassword) R.string.meshshare_hide else R.string.meshshare_show))
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(cfg.password)) }) {
          Text(stringResource(R.string.meshshare_copy_password))
        }
        if (url != null) {
          TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
            Text(stringResource(R.string.meshshare_copy_link))
          }
        }
        TextButton(onClick = { model.newPassword() }) {
          Text(stringResource(R.string.meshshare_new_password), color = MaterialTheme.colorScheme.secondary)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
      }

      ShareCard {
        Text(
            stringResource(R.string.meshshare_files_heading, files.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
          Text(stringResource(if (busy) R.string.meshshare_adding else R.string.meshshare_add))
        }
        if (files.isEmpty()) {
          Text(stringResource(R.string.meshshare_empty), style = MaterialTheme.typography.bodySmall)
        }
        files.forEach { f ->
          Text("${f.name} · ${humanBytes(f.size)}", style = MaterialTheme.typography.bodyMedium)
          TextButton(onClick = { model.remove(f.name) }) {
            Text(stringResource(R.string.meshshare_remove), color = MaterialTheme.colorScheme.secondary)
          }
        }
      }

      ShareCard {
        Text(
            stringResource(R.string.meshshare_peers_heading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.meshshare_peers_body), style = MaterialTheme.typography.bodySmall)
        model.peers().forEach { peer ->
          TextButton(
              onClick = {
                runCatching {
                  context.startActivity(
                      Intent(Intent.ACTION_VIEW, Uri.parse(model.peerUrl(peer)))
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
              },
              enabled = peer.online,
          ) {
            Text("${peer.name} (${peer.ip})")
          }
        }
      }

      Text(
          stringResource(R.string.meshshare_not_device_verified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun humanBytes(n: Long): String =
    when {
      n >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", n / (1L shl 30).toDouble())
      n >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / (1L shl 20).toDouble())
      n >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", n / (1L shl 10).toDouble())
      else -> "$n B"
    }

@Composable
private fun ShareCard(content: @Composable () -> Unit) {
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
