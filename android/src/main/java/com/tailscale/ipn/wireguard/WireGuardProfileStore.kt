// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.wireguard

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Minimal key/value secret storage so the profile store is testable on the JVM. */
interface SecretStore {
  fun get(key: String): String?

  fun put(key: String, value: String)

  fun remove(key: String)
}

/**
 * Backed by the app's EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys, master
 * key held in the Android Keystore).
 */
class EncryptedPrefsSecretStore(private val prefs: SharedPreferences) : SecretStore {
  override fun get(key: String): String? = prefs.getString(key, null)

  override fun put(key: String, value: String) {
    prefs.edit().putString(key, value).commit()
  }

  override fun remove(key: String) {
    prefs.edit().remove(key).commit()
  }
}

@Serializable data class WireGuardProfile(val id: String, val name: String)

/**
 * Stores WireGuard profiles. The full wg-quick text (including PrivateKey/PresharedKey) only ever
 * lives in the encrypted [SecretStore]; [toString] of the public model never contains it.
 */
class WireGuardProfileStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }
  private val listSer = ListSerializer(WireGuardProfile.serializer())

  fun list(): List<WireGuardProfile> =
      store.get(KEY_PROFILES)?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() }
          ?: emptyList()

  private fun save(list: List<WireGuardProfile>) =
      store.put(KEY_PROFILES, json.encodeToString(listSer, list))

  fun add(name: String, config: String, id: String = UUID.randomUUID().toString()): WireGuardProfile {
    val p = WireGuardProfile(id, uniqueName(name.trim().ifEmpty { "WireGuard" }))
    store.put(confKey(id), config)
    save(list() + p)
    return p
  }

  fun rename(id: String, name: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    save(list().map { if (it.id == id) it.copy(name = n) else it })
  }

  fun delete(id: String) {
    store.remove(confKey(id))
    save(list().filterNot { it.id == id })
    if (activeProfileId() == id) store.remove(KEY_ACTIVE)
  }

  fun config(id: String): String? = store.get(confKey(id))

  fun activeProfileId(): String? = store.get(KEY_ACTIVE)

  fun mode(): TunnelMode =
      store.get(KEY_MODE)?.let { runCatching { TunnelMode.valueOf(it) }.getOrNull() }
          ?: TunnelMode.MESH

  fun setMode(mode: TunnelMode, activeProfileId: String?) {
    store.put(KEY_MODE, mode.name)
    if (activeProfileId != null) store.put(KEY_ACTIVE, activeProfileId) else store.remove(KEY_ACTIVE)
  }

  private fun uniqueName(base: String): String {
    val names = list().map { it.name }.toSet()
    if (base !in names) return base
    var i = 2
    while ("$base ($i)" in names) i++
    return "$base ($i)"
  }

  companion object {
    private const val KEY_PROFILES = "wg.profiles"
    private const val KEY_MODE = "wg.mode"
    private const val KEY_ACTIVE = "wg.active"

    private fun confKey(id: String) = "wg.conf.$id"

    /** Best-effort profile name from a file name like "office.conf". */
    fun nameFromFileName(fileName: String?): String =
        fileName?.substringAfterLast('/')?.removeSuffix(".conf")?.trim()?.ifEmpty { null }
            ?: "WireGuard"
  }
}
