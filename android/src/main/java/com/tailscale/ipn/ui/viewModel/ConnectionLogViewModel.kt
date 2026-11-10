// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.connlog.ConnectionLogger
import com.tailscale.ipn.connlog.LogEntry
import com.tailscale.ipn.ui.util.set
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConnectionLogViewModel : ViewModel() {
  val enabled: StateFlow<Boolean> = MutableStateFlow(ConnectionLogger.enabled())
  /** Newest first. */
  val entries: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
  val message: StateFlow<String?> = MutableStateFlow(null)

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      entries.set(runCatching { ConnectionLogger.logFile().entries().asReversed() }.getOrDefault(emptyList()))
    }
  }

  fun setEnabled(on: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      ConnectionLogger.setEnabled(on)
      enabled.set(on)
      refresh()
    }
  }

  fun clear() {
    viewModelScope.launch(Dispatchers.IO) {
      ConnectionLogger.clear()
      refresh()
      message.set("Log cleared")
    }
  }

  fun time(e: LogEntry): String =
      DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(e.atMs))

  fun copy() {
    val text = entries.value.joinToString("\n") { "${time(it)}  ${it.kind.name}  ${it.text}" }
    App.get().getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("connection log", text))
    message.set("Copied ${entries.value.size} entries (no IP addresses)")
  }
}
