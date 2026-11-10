// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.battery.BatterySaverConfig
import com.tailscale.ipn.battery.BatterySaverMonitor
import com.tailscale.ipn.battery.PowerState
import com.tailscale.ipn.battery.SaverReason
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BatterySaverViewModel : ViewModel() {
  val config: StateFlow<BatterySaverConfig> = MutableStateFlow(BatterySaverMonitor.config())
  val reason: StateFlow<SaverReason> = BatterySaverMonitor.reason
  val power: StateFlow<PowerState> = BatterySaverMonitor.power

  fun update(c: BatterySaverConfig) {
    BatterySaverMonitor.save(App.get(), c)
    config.set(BatterySaverMonitor.config())
  }
}
