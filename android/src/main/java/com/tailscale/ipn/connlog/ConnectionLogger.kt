// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.connlog

import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import java.io.File

/**
 * App-wide entry point. Off by default; nothing is written unless the user
 * turns it on. The file lives in no-backup storage and is never part of a
 * settings backup (the backup payload is a fixed non-secret whitelist).
 */
object ConnectionLogger {
  const val KEY_ENABLED = "connlog.enabled"
  private const val FILE_NAME = "connection-log.jsonl"

  @Volatile private var enabledCache: Boolean? = null
  private var file: ConnectionLogFile? = null

  private fun prefs() = EncryptedPrefsSecretStore(App.get().getEncryptedPrefs())

  fun enabled(): Boolean =
      enabledCache ?: (runCatching { prefs().get(KEY_ENABLED) == "true" }.getOrDefault(false)).also { enabledCache = it }

  fun setEnabled(on: Boolean) {
    prefs().put(KEY_ENABLED, on.toString())
    enabledCache = on
    if (on) log(LogKind.INFO, "Connection log turned on", force = true)
  }

  @Synchronized
  fun logFile(): ConnectionLogFile =
      file ?: ConnectionLogFile(File(App.get().noBackupFilesDir, FILE_NAME)).also { file = it }

  /** Never throws: logging must not affect connect, kill switch, or routing. */
  fun log(kind: LogKind, text: String, force: Boolean = false) {
    runCatching {
      if (!force && !enabled()) return
      logFile().add(LogEntry(System.currentTimeMillis(), kind, text))
    }
  }

  fun clear() = runCatching { logFile().clear() }
}
