// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
// Victorious Mesh: settings screen for the Headscale control server URL.

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.ControlServer

@Composable
fun ControlServerView(onBack: BackNavigation, onNavigateToAuthKey: () -> Unit) {
  var url by remember { mutableStateOf(ControlServer.get()) }
  var saved by remember { mutableStateOf(false) }
  val valid = url.isBlank() || ControlServer.isValid(url)
  val portWarning = url.isNotBlank() && valid && !ControlServer.usesPort443(url)

  Scaffold(topBar = { Header(R.string.vm_control_server, onBack = onBack) }) { innerPadding ->
    Column(
        modifier =
            Modifier.padding(innerPadding).padding(16.dp).verticalScroll(rememberScrollState())
    ) {
      Text(stringResource(R.string.vm_control_server_desc), style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = url,
          onValueChange = {
            url = it
            saved = false
          },
          singleLine = true,
          isError = !valid,
          label = { Text(stringResource(R.string.custom_control_url_title)) },
          placeholder = { Text(stringResource(R.string.custom_control_placeholder)) },
          keyboardOptions =
              KeyboardOptions(
                  capitalization = KeyboardCapitalization.None,
                  keyboardType = KeyboardType.Uri,
              ),
      )
      if (portWarning) {
        Text(
            stringResource(R.string.vm_control_server_port_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Spacer(Modifier.height(12.dp))
      Button(
          enabled = valid,
          onClick = {
            ControlServer.set(url)
            saved = true
          },
      ) {
        Text(
            stringResource(if (saved) R.string.vm_control_server_saved else R.string.vm_control_server_save)
        )
      }
      Spacer(Modifier.height(24.dp))
      Text(stringResource(R.string.vm_login_with_preauth_key_desc), style = MaterialTheme.typography.bodySmall)
      Spacer(Modifier.height(8.dp))
      OutlinedButton(
          enabled = ControlServer.get().isNotBlank(),
          onClick = onNavigateToAuthKey,
      ) {
        Text(stringResource(R.string.vm_login_with_preauth_key))
      }
    }
  }
}
