// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.killswitch.KillSwitchStore
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wol.WakeOnLanPlanner
import com.tailscale.ipn.wol.WolDevice
import com.tailscale.ipn.wol.WolRoute
import com.tailscale.ipn.wol.WolSender
import com.tailscale.ipn.wol.WolStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class WolPeer(val id: String, val name: String)

class WakeOnLanViewModel(app: Application) : AndroidViewModel(app) {
  private val store = WolStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  val devices: StateFlow<List<WolDevice>> = MutableStateFlow(store.list())
  val message: StateFlow<String?> = MutableStateFlow(null)

  fun peers(): List<WolPeer> =
      Notifier.netmap.value?.Peers.orEmpty().map { WolPeer(it.StableID, it.ComputedName ?: it.Name) }

  fun peerName(id: String): String = peers().firstOrNull { it.id == id }?.name ?: "that mesh device"

  private fun meshUp(): Boolean = App.get().getAppScopedViewModel().vpnActive.value

  /** Kill switch armed and the mesh is down: everything is being held. */
  private fun killSwitchBlocking(): Boolean {
    val ks = KillSwitchStore.fromApp().config()
    return ks.enabled && ks.armed && !meshUp()
  }

  fun problem(d: WolDevice): String? = WakeOnLanPlanner.problem(d, killSwitchBlocking(), meshUp())

  /** Saving only checks the device details, not whether it can be sent right now. */
  fun save(d: WolDevice): Boolean {
    val p = WakeOnLanPlanner.problem(d, killSwitchBlocking = false, meshUp = true)
    if (p != null) {
      message.set("Not saved: $p")
      return false
    }
    store.save(d)
    devices.set(store.list())
    message.set("Saved ${d.name.trim()}")
    return true
  }

  fun delete(id: String) {
    store.delete(id)
    devices.set(store.list())
  }

  fun wake(d: WolDevice) {
    val p = problem(d)
    if (p != null) {
      message.set("Not sent: $p")
      return
    }
    if (d.route == WolRoute.PEER_COMMAND) {
      val cmd = WakeOnLanPlanner.peerCommand(d) ?: return
      App.get().getSystemService(ClipboardManager::class.java)
          ?.setPrimaryClip(ClipData.newPlainText("wake command", cmd))
      message.set("Copied. Run this on ${peerName(d.peerNodeId)} (for example over Remote shell): $cmd")
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        if (d.route == WolRoute.LAN) WolSender.sendLan(d) else WolSender.sendMesh(d)
        message.set("Magic packet sent to ${d.name}")
      } catch (e: Exception) {
        message.set("Not sent: ${e.message ?: e.javaClass.simpleName}")
      }
    }
  }
}
