// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.autoconnect

import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.SecretStore

data class AutoConnectConfig(val enabled: Boolean = false) {
  override fun toString(): String = "AutoConnectConfig(enabled=$enabled)"
}

class AutoConnectStore(private val store: SecretStore) {
  fun config(): AutoConnectConfig = AutoConnectConfig(enabled = store.get(KEY_ENABLED) == "1")

  fun enabled(): Boolean = config().enabled

  fun setEnabled(enabled: Boolean) {
    store.put(KEY_ENABLED, if (enabled) "1" else "0")
  }

  companion object {
    private const val KEY_ENABLED = "autoconnect.enabled"

    fun fromApp(): AutoConnectStore =
        AutoConnectStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))
  }
}
