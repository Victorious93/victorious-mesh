// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.meshhealth.MeshHealthInputs
import com.tailscale.ipn.meshhealth.MeshHealthPlanner
import com.tailscale.ipn.meshhealth.MeshHealthReport
import com.tailscale.ipn.meshhealth.MeshPeerSnapshot
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.WireGuardManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MeshHealthViewModel(app: Application) : AndroidViewModel(app) {
  val report: StateFlow<MeshHealthReport?> = MutableStateFlow(null)
  val checking: StateFlow<Boolean> = MutableStateFlow(false)

  fun runCheck() {
    if (checking.value) return
    checking.set(true)
    report.set(null)

    val vpnActive =
        runCatching { App.get().getAppScopedViewModel().vpnActive.value }.getOrDefault(false)
    val wireGuardActive = runCatching { WireGuardManager.isWireGuardActive() }.getOrDefault(false)
    val notifierWarnings =
        App.get()
            .healthNotifier
            ?.currentWarnings
            ?.value
            .orEmpty()
            .sorted()
            .map { warning -> warning.Title.ifBlank { warning.Text } }
            .filter { it.isNotBlank() }
    val engine = Notifier.engineStatus.value

    Client(viewModelScope).status { result ->
      result.onSuccess { status ->
        val peers = snapshots(status)
        val pingTarget = MeshHealthPlanner.firstOnlineIPv4(peers)
        val warningTitles =
            (notifierWarnings + status.Health.orEmpty().filter { it.isNotBlank() }).distinct()
        val inputsWithoutPing =
            MeshHealthInputs(
                vpnActive = vpnActive,
                wireGuardActive = wireGuardActive,
                backendState = status.BackendState,
                warningTitles = warningTitles,
                peers = peers,
                rBytes = engine?.RBytes ?: 0L,
                wBytes = engine?.WBytes ?: 0L,
                numLive = engine?.NumLive ?: 0,
                latencyMs = null,
                latencyError = null,
                pingTarget = pingTarget,
            )
        if (!vpnActive || wireGuardActive || pingTarget == null) {
          finish(MeshHealthPlanner.report(inputsWithoutPing))
          return@onSuccess
        }
        Client(viewModelScope).ping(pingTarget) { pingResult ->
          var latencyMs: Double? = null
          var latencyError: String? = null
          pingResult.onSuccess { ping ->
            if (ping.Err.isNotEmpty()) {
              latencyError = ping.Err
            } else {
              latencyMs = ping.LatencySeconds * 1000
            }
          }
          pingResult.onFailure { error ->
            latencyError = error.message ?: error.javaClass.simpleName
          }
          finish(
              MeshHealthPlanner.report(
                  inputsWithoutPing.copy(latencyMs = latencyMs, latencyError = latencyError)
              )
          )
        }
      }
      result.onFailure { error ->
        finish(
            MeshHealthPlanner.report(
                MeshHealthInputs(
                    vpnActive = vpnActive,
                    wireGuardActive = wireGuardActive,
                    backendState = "",
                    warningTitles = notifierWarnings,
                    peers = emptyList(),
                    rBytes = engine?.RBytes ?: 0L,
                    wBytes = engine?.WBytes ?: 0L,
                    numLive = engine?.NumLive ?: 0,
                    latencyMs = null,
                    latencyError = null,
                    pingTarget = null,
                    statusError = error.message ?: error.javaClass.simpleName,
                )
            )
        )
      }
    }
  }

  private fun finish(value: MeshHealthReport) {
    report.set(value)
    checking.set(false)
  }

  private fun snapshots(status: IpnState.Status): List<MeshPeerSnapshot> =
      status.Peer?.values.orEmpty().map { peer ->
        MeshPeerSnapshot(
            online = peer.Online,
            curAddr = peer.CurAddr.orEmpty(),
            relay = peer.Relay.orEmpty(),
            peerRelay = peer.PeerRelay.orEmpty(),
            ipv4 = MeshHealthPlanner.ipv4FromAddresses(peer.TailscaleIPs),
        )
      }
}
