// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.victorious.mesh.R
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.wireguard.TunnelMode
import com.tailscale.ipn.wireguard.WireGuardManager
import com.tailscale.ipn.wireguard.WireGuardProfile
import com.tailscale.ipn.wireguard.WireGuardProfileStore
import java.io.ByteArrayOutputStream
import libtailscale.Libtailscale

private const val WG_VIEW_TAG = "WireGuardView"
private const val MAX_CONF_BYTES = 64 * 1024

/** Validates via the Go parser; returns an error message or null. Never echoes the config. */
private fun validate(conf: String): String? =
    try {
      Libtailscale.validateWireGuardConfig(conf)
      null
    } catch (e: Exception) {
      e.message ?: "Invalid WireGuard config"
    }

private fun readText(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.openInputStream(uri)?.use { s ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(8192)
      while (true) {
        val n = s.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > MAX_CONF_BYTES) return@use null
      }
      out.toString("UTF-8")
    }

private fun displayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    }

/** Decodes a WireGuard QR code from an image (zxing-core is already an app dependency). */
private fun decodeQr(ctx: Context, uri: Uri): String? =
    try {
      val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      bmp?.let {
        val px = IntArray(it.width * it.height)
        it.getPixels(px, 0, it.width, 0, 0, it.width, it.height)
        MultiFormatReader()
            .decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(it.width, it.height, px))))
            .text
      }
    } catch (e: Exception) {
      null
    }

@Composable
fun WireGuardView(onBack: () -> Unit) {
  val ctx = LocalContext.current
  val store = remember {
    WireGuardManager.load()
    WireGuardManager.store()
  }
  var profiles by remember { mutableStateOf(store.list()) }
  val state by WireGuardManager.state.collectAsState()
  val lastError by WireGuardManager.lastError.collectAsState()
  var message by remember { mutableStateOf<String?>(null) }
  var showPaste by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf<WireGuardProfile?>(null) }
  var deleting by remember { mutableStateOf<WireGuardProfile?>(null) }
  var pendingConnect by remember { mutableStateOf<String?>(null) }

  fun refresh() {
    profiles = store.list()
  }

  fun importConf(name: String, conf: String?) {
    if (conf == null) {
      message = "Could not read the file (too large or unreadable)"
      return
    }
    val err = validate(conf)
    if (err != null) {
      message = "Invalid config: $err"
      return
    }
    store.add(name, conf)
    refresh()
    message = "Imported \"$name\""
  }

  val vpnConsent =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingConnect
        pendingConnect = null
        if (r.resultCode == Activity.RESULT_OK && id != null) WireGuardManager.connect(ctx, id)
        else message = "VPN permission is required"
      }

  fun connect(id: String) {
    val consent = VpnService.prepare(ctx)
    if (consent != null) {
      pendingConnect = id
      vpnConsent.launch(consent)
    } else WireGuardManager.connect(ctx, id)
  }

  val openFile =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          val name = WireGuardProfileStore.nameFromFileName(displayName(ctx, uri))
          importConf(name, runCatching { readText(ctx, uri) }.getOrNull())
        }
      }
  val openQr =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          val text = decodeQr(ctx, uri)
          if (text == null) message = "No QR code found in that image"
          else importConf("WireGuard (QR)", text)
        }
      }

  Scaffold(topBar = { Header(R.string.wireguard_title, onBack = onBack) }) { inner ->
    LazyColumn(
        modifier = Modifier.padding(inner).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item { ModeIndicator(state.mode, state.running, profiles.find { it.id == state.wireGuardProfileId }?.name) }
      (lastError ?: message)?.let { m ->
        item { Text(m, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Import .conf") }
          OutlinedButton(onClick = { showPaste = true }) { Text("Paste") }
          OutlinedButton(onClick = { openQr.launch(arrayOf("image/*")) }) { Text("QR image") }
        }
      }
      if (state.mode == TunnelMode.WIREGUARD) {
        item {
          TextButton(onClick = { WireGuardManager.useMesh(ctx) }) { Text("Switch back to mesh mode") }
        }
      }
      if (profiles.isEmpty()) {
        item {
          Text(
              "No WireGuard profiles yet. Import a wg-quick .conf file, paste one, or scan a QR image.",
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      items(profiles, key = { it.id }) { p ->
        val active = state.running && state.mode == TunnelMode.WIREGUARD && state.wireGuardProfileId == p.id
        ProfileCard(
            p,
            active,
            onConnect = { connect(p.id) },
            onDisconnect = { WireGuardManager.disconnect(ctx) },
            onRename = { renaming = p },
            onDelete = { deleting = p },
        )
      }
    }
  }

  if (showPaste) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { showPaste = false },
        title = { Text("Paste WireGuard config") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(
                text,
                { text = it },
                label = { Text("[Interface] … [Peer] …") },
                modifier = Modifier.height(200.dp),
            )
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                showPaste = false
                importConf(name.ifBlank { "WireGuard" }, text)
              }) {
                Text("Save")
              }
        },
        dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
    )
  }
  renaming?.let { p ->
    var name by remember(p.id) { mutableStateOf(p.name) }
    AlertDialog(
        onDismissRequest = { renaming = null },
        title = { Text("Rename profile") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = {
          TextButton(
              onClick = {
                store.rename(p.id, name)
                refresh()
                renaming = null
              }) {
                Text("Save")
              }
        },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
    )
  }
  deleting?.let { p ->
    AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("Delete \"${p.name}\"?") },
        text = { Text("The profile and its keys are removed from this device.") },
        confirmButton = {
          TextButton(
              onClick = {
                if (state.running && state.wireGuardProfileId == p.id) WireGuardManager.disconnect(ctx)
                store.delete(p.id)
                refresh()
                deleting = null
                TSLog.d(WG_VIEW_TAG, "profile deleted")
              }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
              }
        },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun neonBrush() =
    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))

@Composable
fun WireGuardActiveBanner(onOpen: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable { onOpen() },
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(neonBrush())
            .padding(2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
      Column {
        Text(
            stringResource(R.string.wireguard_active_banner_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.wireguard_active_banner_body),
            style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

@Composable
fun ModeIndicator(mode: TunnelMode, running: Boolean, profileName: String?) {
  Box(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(neonBrush())
          .padding(2.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(16.dp)) {
        Column {
          Text("Tunnel mode", style = MaterialTheme.typography.labelMedium)
          Text(
              when (mode) {
                TunnelMode.MESH -> "Mesh"
                TunnelMode.WIREGUARD -> "WireGuard" + (profileName?.let { " · $it" } ?: "")
              },
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
          )
          Text(
              if (running) "Connected" else "Disconnected",
              style = MaterialTheme.typography.bodyMedium,
              color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
          )
        }
      }
}

@Composable
private fun ProfileCard(
    p: WireGuardProfile,
    active: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      Text(p.name, style = MaterialTheme.typography.titleMedium)
      Row(
          Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (active) {
          Button(onClick = onDisconnect) { Text("Disconnect") }
        } else {
          Box(Modifier.clip(RoundedCornerShape(50)).background(neonBrush())) {
            Button(
                onClick = onConnect,
                colors =
                    ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Black),
            ) {
              Text("Connect")
            }
          }
        }
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = onDelete) { Text("Delete") }
      }
    }
  }
}
