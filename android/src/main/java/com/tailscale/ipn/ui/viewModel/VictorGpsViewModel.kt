// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.victorgps.GpsProfilePlanner
import com.tailscale.ipn.victorgps.GpsProfileStore
import com.tailscale.ipn.victorgps.GpsRoomProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VictorGpsViewModel(app: Application) : AndroidViewModel(app) {
  private val store = GpsProfileStore.fromApp()

  val profile: StateFlow<GpsRoomProfile> = MutableStateFlow(store.profile())
  val message: StateFlow<String?> = MutableStateFlow(null)

  fun save(enabled: Boolean, lat: String, lng: String, target: String) {
    val next =
        GpsRoomProfile(
            enabled = enabled,
            latitude = lat.trim(),
            longitude = lng.trim(),
            targetPackage = target.trim(),
        )
    if (enabled && !GpsProfilePlanner.canEnable(next)) {
      message.set("Need a valid latitude, longitude, and optional package")
      store.save(next.copy(enabled = false))
    } else {
      store.save(next)
      message.set(null)
    }
    profile.set(store.profile())
  }
}
