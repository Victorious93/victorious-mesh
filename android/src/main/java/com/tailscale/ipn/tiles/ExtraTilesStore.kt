// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.tiles

import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.SecretStore

data class ExtraTilesConfig(val enabled: Boolean = false) {
  override fun toString(): String = "ExtraTilesConfig(enabled=$enabled)"
}

class ExtraTilesStore(private val store: SecretStore) {
  fun config(): ExtraTilesConfig = ExtraTilesConfig(enabled = store.get(KEY_ENABLED) == "1")

  fun enabled(): Boolean = config().enabled

  fun setEnabled(enabled: Boolean) {
    store.put(KEY_ENABLED, if (enabled) "1" else "0")
  }

  companion object {
    private const val KEY_ENABLED = "extratiles.enabled"

    fun fromApp(): ExtraTilesStore =
        ExtraTilesStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))
  }
}
