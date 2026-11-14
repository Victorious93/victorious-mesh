// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.dns.DnsToolsPlanner
import com.tailscale.ipn.dns.ResolverProfile
import com.tailscale.ipn.dns.ResolverProfileStore
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import libtailscale.Libtailscale

/** Lookup tools over the resolver profiles. Nothing runs until the user taps a button. */
class DnsToolsViewModel : ViewModel() {
  private val store = ResolverProfileStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))

  val profiles: StateFlow<List<ResolverProfile>> = MutableStateFlow(store.all())
  val selectedId: StateFlow<String> = MutableStateFlow(DnsToolsPlanner.presets.first().id)
  val output: StateFlow<String> = MutableStateFlow("")
  val busy: StateFlow<Boolean> = MutableStateFlow(false)
  val message: StateFlow<String?> = MutableStateFlow(null)
  val historyOn: StateFlow<Boolean> = MutableStateFlow(false)
  val cacheSize: StateFlow<Int> = MutableStateFlow(0)
  val guardOn: StateFlow<Boolean> = MutableStateFlow(com.tailscale.ipn.proxy.MeshProxyController.reservedGuardEnabled())

  /** The always-on .onion / .i2p DNS guard (on by default). */
  fun setGuard(on: Boolean) {
    com.tailscale.ipn.proxy.MeshProxyController.setReservedGuard(on)
    guardOn.set(on)
    message.set(if (on) "Guard on: .onion / .i2p names are answered on the phone, never sent to DNS" else "Guard off: .onion / .i2p queries may reach your DNS servers unless a Tor/I2P profile is forced")
  }

  private fun selected(): ResolverProfile = profiles.value.firstOrNull { it.id == selectedId.value } ?: DnsToolsPlanner.presets.first()

  fun select(id: String) = selectedId.set(id)

  fun saveProfile(p: ResolverProfile): Boolean {
    DnsToolsPlanner.problem(p)?.let {
      message.set("Not saved: $it")
      return false
    }
    val saved = store.save(p)
    profiles.set(store.all())
    selectedId.set(saved.id)
    message.set("Saved")
    return true
  }

  fun deleteProfile(id: String) {
    if (id.startsWith("preset-")) return
    store.delete(id)
    profiles.set(store.all())
    if (selectedId.value == id) selectedId.set(DnsToolsPlanner.presets.first().id)
  }

  fun duplicateProfile(id: String) {
    store.duplicate(id)?.let { selectedId.set(it.id) }
    profiles.set(store.all())
  }

  private fun refreshSystemServers() {
    // The core's "Network DNS" profile needs the underlying network's servers.
    runCatching { Libtailscale.setSystemDNSServers(com.tailscale.ipn.proxy.MeshProxyController.currentNetworkDnsServers()) }
  }

  private fun run(block: () -> String) {
    busy.set(true)
    viewModelScope.launch {
      val text = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "error: ${it.message}" } }
      output.set(text)
      busy.set(false)
      cacheSize.set(runCatching { Libtailscale.dnsCacheSize().toInt() }.getOrDefault(0))
    }
  }

  private fun guard(p: ResolverProfile): Boolean {
    DnsToolsPlanner.problem(p)?.let {
      message.set("This profile cannot be used: $it")
      return false
    }
    return true
  }

  fun lookup(name: String, type: String, dnssec: Boolean) {
    val p = selected()
    if (!guard(p) || name.isBlank()) return
    refreshSystemServers()
    run {
      val ip = name.trim()
      val raw =
          if (type == "PTR" && DnsToolsPlanner.isIpLiteral(ip)) Libtailscale.dnsReverse(DnsToolsPlanner.profileJson(p), ip)
          else Libtailscale.dnsQuery(DnsToolsPlanner.profileJson(p), name.trim(), type, if (dnssec) """{"dnssec":true}""" else "")
      renderOne(raw)
    }
  }

  fun compare(name: String, type: String, ids: Set<String>) {
    val chosen = profiles.value.filter { it.id in ids }
    if (chosen.size < 2 || name.isBlank()) {
      message.set("Pick at least two resolvers and enter a name")
      return
    }
    if (!chosen.all { guard(it) }) return
    refreshSystemServers()
    run {
      val o = Json.parseToJsonElement(Libtailscale.dnsCompare(DnsToolsPlanner.profilesJson(chosen), name.trim(), type)).jsonObject
      o["error"]?.let { return@run "error: ${it.jsonPrimitive.content}" }
      buildString {
        o["results"]?.jsonArray?.forEach { appendLine(it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()); appendLine() }
        append(o["summary"]?.jsonPrimitive?.content.orEmpty())
        o["fastest"]?.let { append("\nFastest: ").append(it.jsonPrimitive.content) }
      }
    }
  }

  fun latency(name: String, type: String) {
    val p = selected()
    if (!guard(p) || name.isBlank()) return
    refreshSystemServers()
    run {
      val o = Json.parseToJsonElement(Libtailscale.dnsLatency(DnsToolsPlanner.profileJson(p), name.trim(), type, 5)).jsonObject
      o["error"]?.takeIf { o["ok"]?.jsonPrimitive?.content == "0" || o["samples"] == null }?.let { return@run "error: ${it.jsonPrimitive.content}" }
      "${p.name}: ${o["ok"]?.jsonPrimitive?.content}/${o["samples"]?.jsonPrimitive?.content} answered, min ${fmt(o["minMs"])} ms, median ${fmt(o["medianMs"])} ms, max ${fmt(o["maxMs"])} ms" +
          (o["error"]?.let { "\nlast error: ${it.jsonPrimitive.content}" } ?: "")
    }
  }

  private fun fmt(e: kotlinx.serialization.json.JsonElement?): String = e?.jsonPrimitive?.content?.toDoubleOrNull()?.let { "%.1f".format(it) } ?: "?"

  private fun renderOne(raw: String): String {
    val o = Json.parseToJsonElement(raw).jsonObject
    o["error"]?.takeIf { o["name"] == null }?.let { return "error: ${it.jsonPrimitive.content}" }
    return o["text"]?.jsonPrimitive?.content ?: raw
  }

  fun clearCache() {
    Libtailscale.dnsClearCache()
    cacheSize.set(0)
    message.set("DNS cache cleared")
  }

  fun setHistory(on: Boolean) {
    Libtailscale.dnsHistoryEnable(on)
    historyOn.set(on)
    if (!on) message.set("History turned off and erased")
  }

  fun showHistory() {
    val arr = Json.parseToJsonElement(Libtailscale.dnsHistoryJSON())
    val items = (arr as? kotlinx.serialization.json.JsonArray)?.map { it as JsonObject } ?: emptyList()
    output.set(
        if (items.isEmpty()) "History is empty (it is off unless you turn it on)."
        else items.joinToString("\n") { "${it["at"]?.jsonPrimitive?.content?.take(19)}  ${it["name"]?.jsonPrimitive?.content} ${it["type"]?.jsonPrimitive?.content}  ${it["rcode"]?.jsonPrimitive?.content ?: "error"}  via ${it["profile"]?.jsonPrimitive?.content}"
        })
  }

  fun clearHistory() {
    Libtailscale.dnsHistoryClear()
    output.set("")
  }

  fun applyLocalRules(hosts: String, allow: String, block: String) {
    DnsToolsPlanner.localPolicyJson(hosts, allow, block).fold(
        onSuccess = {
          runCatching { Libtailscale.dnsSetLocalPolicy(it) }
              .onSuccess { message.set("Local rules applied to the DNS tools (they do not change other apps' DNS)") }
              .onFailure { message.set("Not applied: ${it.message}") }
        },
        onFailure = { message.set("Not applied: ${it.message}") })
  }
}
