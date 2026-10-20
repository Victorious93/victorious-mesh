// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.killswitch.KillSwitchConfig
import com.tailscale.ipn.killswitch.KillSwitchController
import com.tailscale.ipn.killswitch.KillSwitchEvent
import com.tailscale.ipn.tiles.ExtraTilesController
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class KillSwitchViewModel(app: Application) : AndroidViewModel(app) {
  private val store = KillSwitchController.store()

  val config: StateFlow<KillSwitchConfig> = MutableStateFlow(store.config())

  fun setEnabled(enabled: Boolean) {
    store.setEnabled(enabled)
    val vpnRunning = App.get().getAppScopedViewModel().vpnActive.value
    val event = if (enabled) KillSwitchEvent.TOGGLE_ON else KillSwitchEvent.TOGGLE_OFF
    KillSwitchController.apply(event, vpnRunning)
    ExtraTilesController.refreshSurfaces(getApplication())
    config.set(store.config())
  }
}
