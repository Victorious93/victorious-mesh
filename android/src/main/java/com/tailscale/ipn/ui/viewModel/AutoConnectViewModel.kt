// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.autoconnect.AutoConnectConfig
import com.tailscale.ipn.autoconnect.AutoConnectController
import com.tailscale.ipn.autoconnect.AutoConnectEvent
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AutoConnectViewModel(app: Application) : AndroidViewModel(app) {
  private val store = AutoConnectController.store()

  val config: StateFlow<AutoConnectConfig> = MutableStateFlow(store.config())

  fun setEnabled(enabled: Boolean) {
    store.setEnabled(enabled)
    if (enabled) {
      AutoConnectController.maybeStart(getApplication(), AutoConnectEvent.TOGGLE_ON)
    }
    config.set(store.config())
  }
}
