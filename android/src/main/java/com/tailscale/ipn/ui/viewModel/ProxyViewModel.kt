// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tailscale.ipn.proxy.MeshProxyConfig
import com.tailscale.ipn.proxy.MeshProxyController
import com.tailscale.ipn.proxy.ProxyPlanner
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.proxy.UpstreamProxyConfig
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.WireGuardManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
  private val store = MeshProxyController.store(app)

  val mesh: StateFlow<MeshProxyConfig> = MutableStateFlow(store.mesh())
  val upstream: StateFlow<UpstreamProxyConfig> = MutableStateFlow(store.upstream())
  val running: StateFlow<Boolean> = MutableStateFlow(MeshProxyController.running())
  val message: StateFlow<String?> = MutableStateFlow(null)

  fun meshBindLabel(): String {
    val addrs = Notifier.netmap.value?.SelfNode?.Addresses.orEmpty()
    return ProxyPlanner.firstMeshIPv4(addrs) ?: ""
  }

  fun setMeshEnabled(enabled: Boolean) {
    val next = mesh.value.copy(enabled = enabled)
    store.saveMesh(next)
    mesh.set(next)
    applyMesh()
  }

  fun setMeshPort(raw: String) {
    val port = raw.toIntOrNull() ?: return
    if (!ProxyPlanner.validPort(port)) {
      message.set("Port must be 1024–65535")
      return
    }
    val next = mesh.value.copy(port = port)
    store.saveMesh(next)
    mesh.set(next)
    if (next.enabled) applyMesh()
  }

  fun setMeshAuth(user: String, pass: String) {
    val u = user.trim()
    if (!ProxyPlanner.authComplete(u, pass)) {
      message.set("Set both a username and a password, or leave both empty")
      return
    }
    val next = mesh.value.copy(username = u, password = pass)
    store.saveMesh(next)
    mesh.set(next)
    if (next.enabled) applyMesh()
  }

  fun setUpstream(kind: UpstreamKind, host: String, port: String, user: String, pass: String) {
    val p = port.toIntOrNull() ?: upstream.value.port
    if (kind != UpstreamKind.NONE && !ProxyPlanner.validPort(p)) {
      message.set("Upstream port must be 1024–65535")
      return
    }
    val next =
        UpstreamProxyConfig(kind = kind, host = host.trim(), port = p, username = user.trim(), password = pass)
    store.saveUpstream(next)
    upstream.set(next)
    message.set(
        when (kind) {
          UpstreamKind.NONE -> "Upstream proxy off. Reconnect the mesh for this to apply."
          UpstreamKind.HTTP ->
              "HTTP upstream is advisory (Android 10+). Apps may ignore it. Reconnect the mesh to apply."
          UpstreamKind.SOCKS5 ->
              "SOCKS5 cannot be applied to all apps on this phone. Settings are saved only."
        }
    )
  }

  private fun applyMesh() {
    val ctx = getApplication<Application>()
    if (WireGuardManager.isWireGuardActive()) {
      message.set("Switch back to mesh mode before sharing a proxy")
      MeshProxyController.stop(ctx)
      running.set(false)
      return
    }
    MeshProxyController.sync(ctx)
    val up = MeshProxyController.running()
    running.set(up)
    message.set(
        if (mesh.value.enabled && !up) "Mesh must be connected so the proxy can bind its mesh IP"
        else if (up) "Listening on ${meshBindLabel()}:${mesh.value.port}"
        else null
    )
  }
}
