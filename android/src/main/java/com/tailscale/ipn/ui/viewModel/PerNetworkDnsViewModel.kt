// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.NetworkChangeCallback
import com.tailscale.ipn.dns.CurrentNetwork
import com.tailscale.ipn.dns.DnsRule
import com.tailscale.ipn.dns.PerNetworkDns
import com.tailscale.ipn.dns.PerNetworkDnsConfig
import com.tailscale.ipn.dns.PerNetworkDnsPlanner
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PerNetworkDnsViewModel : ViewModel() {
  val config: StateFlow<PerNetworkDnsConfig> = MutableStateFlow(PerNetworkDns.config())
  val message: StateFlow<String?> = MutableStateFlow(null)
  val active: StateFlow<DnsRule?> = MutableStateFlow(PerNetworkDns.lastApplied)
  val network: StateFlow<CurrentNetwork?> = MutableStateFlow(PerNetworkDns.lastNetwork)

  /** "Use Tailscale DNS" must be on for the mesh resolver to forward to these servers. */
  fun meshDnsOn(): Boolean = Notifier.prefs.value?.CorpDNS == true

  /** Headscale "override local DNS" servers take priority over the phone's own choice. */
  fun serverOverrides(): Boolean = Notifier.netmap.value?.DNS?.Resolvers?.isNotEmpty() == true

  private fun commit(c: PerNetworkDnsConfig, note: String?) {
    PerNetworkDns.save(c)
    config.set(c)
    message.set(note)
    viewModelScope.launch(Dispatchers.IO) {
      NetworkChangeCallback.reapplyDns("perNetworkDns")
      refresh()
    }
  }

  val paused: StateFlow<Boolean> = MutableStateFlow(PerNetworkDns.pausedForForcedProxy)

  fun refresh() {
    paused.set(PerNetworkDns.pausedForForcedProxy)
    active.set(PerNetworkDns.lastApplied)
    network.set(PerNetworkDns.lastNetwork)
  }

  fun setEnabled(on: Boolean) = commit(config.value.copy(enabled = on), null)

  fun save(rule: DnsRule, serversText: String): Boolean {
    val servers = PerNetworkDnsPlanner.parseServers(serversText)
    if (servers == null) {
      message.set(
          "Not saved: use 1 to ${PerNetworkDnsPlanner.MAX_SERVERS} IP addresses like 1.1.1.1, 9.9.9.9 " +
              "(not 100.100.100.100 or loopback)")
      return false
    }
    val r = rule.copy(servers = servers)
    PerNetworkDnsPlanner.problem(r)?.let {
      message.set("Not saved: $it")
      return false
    }
    commit(PerNetworkDnsPlanner.upsert(config.value, r), "Saved")
    return true
  }

  fun delete(id: String) = commit(config.value.copy(rules = config.value.rules.filterNot { it.id == id }), null)
}
