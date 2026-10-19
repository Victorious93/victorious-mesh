// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.wireguard

import android.content.Context
import android.content.Intent
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNService
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide tunnel mode state and the entry points the UI uses to switch modes. */
object WireGuardManager {
  private const val TAG = "WireGuardManager"
  private val _state = MutableStateFlow(TunnelState())
  val state: StateFlow<TunnelState> = _state
  private val _lastError = MutableStateFlow<String?>(null)
  val lastError: StateFlow<String?> = _lastError

  @Volatile private var loaded = false

  fun store(ctx: Context = App.get()): WireGuardProfileStore =
      WireGuardProfileStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  /** Mode is persisted so Always-on / restart paths come back in the right mode. */
  fun load() {
    if (loaded) return
    loaded = true
    runCatching { store() }
        .onSuccess { s ->
          _state.value = _state.value.copy(mode = s.mode(), wireGuardProfileId = s.activeProfileId())
        }
  }

  fun isWireGuardActive(): Boolean {
    load()
    val s = _state.value
    return s.mode == TunnelMode.WIREGUARD && s.running
  }

  /** True if the persisted mode wants WireGuard (used by restart / Always-on paths). */
  fun persistedWireGuardProfile(): String? {
    load()
    val s = _state.value
    return if (s.mode == TunnelMode.WIREGUARD) s.wireGuardProfileId else null
  }

  fun connect(ctx: Context, profileId: String) {
    _lastError.value = null
    val i =
        Intent(ctx, IPNService::class.java).apply {
          action = IPNService.ACTION_START_WIREGUARD
          putExtra(IPNService.EXTRA_WG_PROFILE_ID, profileId)
        }
    try {
      ctx.startForegroundService(i)
    } catch (e: Exception) {
      TSLog.e(TAG, "connect: ${e.javaClass.simpleName}")
      _lastError.value = "Could not start the VPN service"
    }
  }

  fun disconnect(ctx: Context) {
    App.get().stopVPN()
  }

  /** Switch back to mesh mode (does not auto-connect the mesh). */
  fun useMesh(ctx: Context) {
    if (isWireGuardActive()) disconnect(ctx)
    onMode(TunnelMode.MESH, null, running = false)
  }

  // --- called by IPNService ---
  internal fun onWireGuardStarted(profileId: String) {
    onMode(TunnelMode.WIREGUARD, profileId, running = true)
  }

  internal fun onMeshStarting() {
    onMode(TunnelMode.MESH, null, running = true)
  }

  internal fun onStopped() {
    _state.value = _state.value.copy(running = false)
  }

  internal fun onError(msg: String) {
    _lastError.value = msg
    _state.value = _state.value.copy(running = false)
  }

  private fun onMode(mode: TunnelMode, id: String?, running: Boolean) {
    _state.value = TunnelState(mode, running, id)
    runCatching { store().setMode(mode, id) }
  }
}
