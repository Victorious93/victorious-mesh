// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.killswitch

import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.SecretStore

data class KillSwitchConfig(
    val enabled: Boolean = false,
    val armed: Boolean = false,
) {
  override fun toString(): String = "KillSwitchConfig(enabled=$enabled, armed=$armed)"
}

class KillSwitchStore(private val store: SecretStore) {
  fun config(): KillSwitchConfig =
      KillSwitchConfig(
          enabled = store.get(KEY_ENABLED) == "1",
          armed = store.get(KEY_ARMED) == "1",
      )

  fun enabled(): Boolean = config().enabled

  fun armed(): Boolean = config().armed

  fun setEnabled(enabled: Boolean) {
    store.put(KEY_ENABLED, if (enabled) "1" else "0")
    if (!enabled) store.put(KEY_ARMED, "0")
  }

  fun setArmed(armed: Boolean) {
    store.put(KEY_ARMED, if (armed) "1" else "0")
  }

  companion object {
    private const val KEY_ENABLED = "killswitch.enabled"
    private const val KEY_ARMED = "killswitch.armed"

    fun fromApp(): KillSwitchStore =
        KillSwitchStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))
  }
}
