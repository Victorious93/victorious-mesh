// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.tiles.ExtraTilesConfig
import com.tailscale.ipn.tiles.ExtraTilesController
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ExtraTilesViewModel(app: Application) : AndroidViewModel(app) {
  private val store = ExtraTilesController.store()

  val config: StateFlow<ExtraTilesConfig> = MutableStateFlow(store.config())

  fun setEnabled(enabled: Boolean) {
    store.setEnabled(enabled)
    ExtraTilesController.sync(getApplication())
    config.set(store.config())
  }
}
