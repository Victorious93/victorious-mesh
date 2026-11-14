// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.proxy.ForcedDNSStatus
import com.tailscale.ipn.proxy.ForcedIPv6Status
import com.tailscale.ipn.proxy.MeshProxyConfig
import com.tailscale.ipn.proxy.MeshProxyController
import com.tailscale.ipn.proxy.OverlayBackend
import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.SavedProxyPlanner
import com.tailscale.ipn.proxy.ProxyPlanner
import com.tailscale.ipn.proxy.UpstreamKind
import com.tailscale.ipn.proxy.UpstreamProxyConfig
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.WireGuardManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import libtailscale.Libtailscale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
  private val store = MeshProxyController.store(app)

  val mesh: StateFlow<MeshProxyConfig> = MutableStateFlow(store.mesh())
  val upstream: StateFlow<UpstreamProxyConfig> = MutableStateFlow(store.upstream())
  val running: StateFlow<Boolean> = MutableStateFlow(MeshProxyController.running())
  val message: StateFlow<String?> = MutableStateFlow(null)
  val dnsState: StateFlow<ForcedDNSStatus> = MutableStateFlow(MeshProxyController.forcedDNSState())
  val ipv6State: StateFlow<ForcedIPv6Status> = MutableStateFlow(MeshProxyController.forcedIPv6State())
  val testRunning: StateFlow<Boolean> = MutableStateFlow(false)
  val testReport: StateFlow<ProxyTestReport?> = MutableStateFlow(null)

  init {
    viewModelScope.launch {
      while (isActive) {
        refreshForcedPath()
        delay(1_500)
      }
    }
  }

  private fun refreshForcedPath() {
    dnsState.set(MeshProxyController.forcedDNSState())
    ipv6State.set(MeshProxyController.forcedIPv6State())
  }

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

  fun setUpstream(
      kind: UpstreamKind,
      host: String,
      port: String,
      user: String,
      pass: String,
      forced: Boolean = upstream.value.forced,
      failClosed: Boolean = upstream.value.failClosed,
      serverName: String = upstream.value.serverName,
      backend: OverlayBackend = upstream.value.backend,
      outproxy: String = upstream.value.outproxy,
  ) {
    val p = port.toIntOrNull() ?: upstream.value.port
    if (kind != UpstreamKind.NONE && !ProxyPlanner.validRemotePort(p)) {
      message.set("Upstream port must be 1–65535")
      return
    }
    if (kind != UpstreamKind.NONE) {
      val problem =
          SavedProxyPlanner.problem(
              SavedProxy("main", "main", kind, host.trim(), p, user.trim(), pass, serverName.trim(), backend, outproxy.trim()))
      if (problem != null) {
        message.set("Not saved: $problem")
        return
      }
    }
    val u = user.trim()
    if (kind != UpstreamKind.NONE && !ProxyPlanner.authComplete(u, pass)) {
      message.set("Set both a username and a password, or leave both empty")
      return
    }
    val next =
        UpstreamProxyConfig(
            kind = kind,
            host = host.trim(),
            port = p,
            username = u,
            password = pass,
            forced = forced && kind != UpstreamKind.NONE,
            // Tor / I2P gateways never fall back to a direct connection, so the
            // fail-closed switch is forced on for them (shown as such in the UI).
            failClosed = failClosed || backend != OverlayBackend.NONE,
            serverName = serverName.trim(),
            backend = if (kind == UpstreamKind.NONE) OverlayBackend.NONE else backend,
            outproxy = outproxy.trim(),
        )
    val needsRestart = next.forced || upstream.value.forced
    store.saveUpstream(next)
    upstream.set(next)
    val ctx = getApplication<Application>()
    MeshProxyController.syncForced(ctx)
    refreshForcedPath()
    if (needsRestart) {
      App.get().restartVPN()
    }
    message.set(
        when {
          kind == UpstreamKind.NONE -> "Upstream proxy off. Reconnect the mesh for this to apply."
          next.forced ->
              "Forced TUN-to-proxy is on. TCP, public DNS, and IPv6 (or a block) go through the proxy. Reconnect if the mesh is already up."
          kind == UpstreamKind.HTTP ->
              "HTTP upstream is advisory (Android 10+) unless you turn on Force all traffic. Apps may ignore it."
          kind == UpstreamKind.HTTPS ->
              "HTTPS proxies are only used when Force all traffic is on (Android's own proxy setting cannot do TLS to the proxy)."
          kind == UpstreamKind.SAM -> "I2P SAM is only used when Force all traffic is on, and carries TCP streams only."
          else -> "SOCKS5 is save-only unless you turn on Force all traffic."
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

  /**
   * Runs the staged connection test against the current saved profile. It walks
   * reachability, TLS, authentication, negotiation and a real request through
   * the proxy, and reports the first stage that failed.
   */
  fun runTest(targetHost: String) {
    val cfg = upstream.value
    if (cfg.kind == UpstreamKind.NONE || cfg.host.isBlank()) {
      message.set("Choose a proxy kind and host first")
      return
    }
    val target = targetHost.trim()
    if (target.isEmpty()) {
      message.set("Enter a test target (for example example.com, or a .i2p / .onion name)")
      return
    }
    testRunning.set(true)
    testReport.set(null)
    viewModelScope.launch {
      val report =
          withContext(Dispatchers.IO) {
            runCatching {
                  val raw = Libtailscale.proxyTest(MeshProxyController.forcedProfileJson(cfg), targetJson(target, cfg))
                  ProxyTestReport.parse(raw)
                }
                .getOrElse { ProxyTestReport(error = it.message ?: "test failed") }
          }
      testReport.set(report)
      testRunning.set(false)
    }
  }

  private fun targetJson(target: String, cfg: UpstreamProxyConfig): String =
      buildJsonObject {
            val host = target.substringBeforeLast(':', target).trim('[', ']')
            val port = target.substringAfterLast(':', "").toIntOrNull()
            put("host", if (port != null) host else target)
            val i2p = target.endsWith(".i2p") || cfg.backend == OverlayBackend.I2P_SAM
            if (port != null) put("port", port)
            // Plain HTTP for eepsites (I2P gives its own encryption); HTTPS for the rest.
            put("tls", !i2p && !target.endsWith(".onion"))
            put("tor", cfg.backend == OverlayBackend.TOR && target == "check.torproject.org")
            if (target == "check.torproject.org") put("path", "/api/ip")
          }
          .toString()
}

/** Parsed result of a staged proxy test. */
data class ProxyTestStep(val stage: String, val ok: Boolean, val skipped: Boolean, val detail: String, val ms: Long)

data class ProxyTestReport(
    val steps: List<ProxyTestStep> = emptyList(),
    val verdict: String = "",
    val isTor: Boolean? = null,
    val error: String? = null,
) {
  val passed: Boolean get() = error == null && verdict == "ok"

  companion object {
    fun parse(raw: String): ProxyTestReport {
      val o = Json.parseToJsonElement(raw).jsonObject
      o["error"]?.let { return ProxyTestReport(error = it.jsonPrimitive.content) }
      val steps =
          (o["steps"] as? JsonArray)?.map {
            val s = it.jsonObject
            ProxyTestStep(
                stage = s["stage"]?.jsonPrimitive?.content.orEmpty(),
                ok = s["ok"]?.jsonPrimitive?.boolean ?: false,
                skipped = s["skipped"]?.jsonPrimitive?.boolean ?: false,
                detail = s["detail"]?.jsonPrimitive?.content.orEmpty(),
                ms = s["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            )
          } ?: emptyList()
      return ProxyTestReport(steps, o["verdict"]?.jsonPrimitive?.content.orEmpty(), o["isTor"]?.jsonPrimitive?.boolean)
    }
  }
}
