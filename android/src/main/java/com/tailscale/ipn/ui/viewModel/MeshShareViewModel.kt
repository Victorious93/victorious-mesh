// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.share.MeshShareConfig
import com.tailscale.ipn.share.MeshShareController
import com.tailscale.ipn.share.MeshSharePlanner
import com.tailscale.ipn.share.SharedFile
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SharePeer(val name: String, val ip: String, val online: Boolean)

class MeshShareViewModel(app: Application) : AndroidViewModel(app) {
  private val store = MeshShareController.store()

  val config: StateFlow<MeshShareConfig> = MutableStateFlow(store.config())
  val running: StateFlow<Boolean> = MutableStateFlow(MeshShareController.running())
  val files: StateFlow<List<SharedFile>> = MutableStateFlow(MeshShareController.files(app))
  val message: StateFlow<String?> = MutableStateFlow(null)
  val busy: StateFlow<Boolean> = MutableStateFlow(false)

  fun meshIp(): String? = MeshShareController.meshIp()

  fun shareUrl(): String? = meshIp()?.let { MeshSharePlanner.shareUrl(it, config.value.port) }

  fun peers(): List<SharePeer> =
      Notifier.netmap.value?.Peers.orEmpty().mapNotNull { p ->
        MeshSharePlanner.peerIPv4(p.Addresses)?.let { SharePeer(p.ComputedName ?: p.Name, it, p.Online != false) }
      }

  fun peerUrl(peer: SharePeer): String = MeshSharePlanner.shareUrl(peer.ip, config.value.port)

  private fun refresh() {
    val app = App.get()
    MeshShareController.sync(app)
    config.set(store.config())
    running.set(MeshShareController.running())
    files.set(MeshShareController.files(app))
  }

  fun setEnabled(on: Boolean) {
    store.setEnabled(on)
    refresh()
  }

  fun setPort(text: String) {
    val p = text.toIntOrNull()
    if (p == null || !MeshSharePlanner.validPort(p)) {
      message.set("Port must be 1024–65535")
      return
    }
    store.setPort(p)
    message.set(null)
    refresh()
  }

  fun newPassword() {
    store.newPassword()
    refresh()
    message.set("New share password set; old links stop working")
  }

  fun addFiles(uris: List<Uri>) {
    if (uris.isEmpty() || busy.value) return
    busy.set(true)
    viewModelScope.launch(Dispatchers.IO) {
      val result = MeshShareController.addFiles(App.get(), uris)
      files.set(MeshShareController.files(App.get()))
      message.set(result)
      busy.set(false)
    }
  }

  fun remove(name: String) {
    MeshShareController.remove(App.get(), name)
    files.set(MeshShareController.files(App.get()))
  }
}
