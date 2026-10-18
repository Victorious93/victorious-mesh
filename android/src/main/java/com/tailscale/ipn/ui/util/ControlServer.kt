// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
// Victorious Mesh: user-configurable Headscale control server.

package com.tailscale.ipn.ui.util

import android.content.Context
import android.net.Uri
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings

/**
 * Stores the control (login) server URL the user typed into Settings. It is applied as the
 * ControlURL pref on every login. Default is empty: the user must set their Headscale URL
 * (https, port 443) before logging in. An MDM "LoginURL" policy, if present, still wins.
 */
object ControlServer {
  private const val PREFS = "victorious_mesh"
  private const val KEY_URL = "controlServerURL"

  private fun prefs() = UninitializedApp.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun get(): String {
    val mdm = MDMSettings.loginURL.flow.value.value
    if (!mdm.isNullOrBlank()) return mdm
    return prefs().getString(KEY_URL, "") ?: ""
  }

  fun set(url: String) {
    prefs().edit().putString(KEY_URL, url.trim().trimEnd('/')).apply()
  }

  /** Basic validation: http(s) scheme with a host. */
  fun isValid(url: String): Boolean {
    val u = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
    return (u.scheme == "https" || u.scheme == "http") && !u.host.isNullOrBlank()
  }

  /** True when the URL resolves to port 443 (https with no explicit port, or :443). */
  fun usesPort443(url: String): Boolean {
    val u = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
    return u.port == 443 || (u.port == -1 && u.scheme == "https")
  }
}
