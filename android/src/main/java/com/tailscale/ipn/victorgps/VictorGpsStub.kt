// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.victorgps

import com.tailscale.ipn.App
import com.tailscale.ipn.wireguard.EncryptedPrefsSecretStore
import com.tailscale.ipn.wireguard.SecretStore

/**
 * Local stub for a VictorGPS room profile (lat/lng + optional target-app
 * package). Off by default. Does **not** mock location and is **not** on
 * the fleet allowlist. Package and debug cert are recorded for pairing
 * notes only (Victorious93/victorgps PR #1).
 */
const val VICTOR_GPS_PACKAGE = "com.victorious.gps"

const val VICTOR_GPS_DEBUG_CERT =
    "38:AE:B4:F2:B9:A6:40:C5:46:FB:5D:DC:45:EA:79:BE:8E:89:BC:2C:76:9D:3C:87:B6:4B:F9:7A:27:B0:38:49"

data class GpsRoomProfile(
    val enabled: Boolean = false,
    val latitude: String = "",
    val longitude: String = "",
    val targetPackage: String = "",
) {
  override fun toString(): String =
      "GpsRoomProfile(enabled=$enabled, lat=${latitude.isNotEmpty()}, lng=${longitude.isNotEmpty()}, target=${targetPackage.isNotEmpty()})"
}

object GpsProfilePlanner {
  private val PKG = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

  fun validLatitude(raw: String): Boolean {
    if (raw.isBlank()) return false
    val v = raw.toDoubleOrNull() ?: return false
    return v in -90.0..90.0
  }

  fun validLongitude(raw: String): Boolean {
    if (raw.isBlank()) return false
    val v = raw.toDoubleOrNull() ?: return false
    return v in -180.0..180.0
  }

  fun validTargetPackage(pkg: String): Boolean = pkg.isEmpty() || PKG.matches(pkg)

  fun canEnable(profile: GpsRoomProfile): Boolean =
      validLatitude(profile.latitude) &&
          validLongitude(profile.longitude) &&
          validTargetPackage(profile.targetPackage)
}

class GpsProfileStore(private val store: SecretStore) {
  fun profile(): GpsRoomProfile =
      GpsRoomProfile(
          enabled = store.get(KEY_ENABLED) == "1",
          latitude = store.get(KEY_LAT).orEmpty(),
          longitude = store.get(KEY_LNG).orEmpty(),
          targetPackage = store.get(KEY_TARGET).orEmpty(),
      )

  fun save(profile: GpsRoomProfile) {
    val enabled = profile.enabled && GpsProfilePlanner.canEnable(profile)
    store.put(KEY_ENABLED, if (enabled) "1" else "0")
    store.put(KEY_LAT, profile.latitude.trim())
    store.put(KEY_LNG, profile.longitude.trim())
    store.put(KEY_TARGET, profile.targetPackage.trim())
  }

  companion object {
    private const val KEY_ENABLED = "victorgps.enabled"
    private const val KEY_LAT = "victorgps.lat"
    private const val KEY_LNG = "victorgps.lng"
    private const val KEY_TARGET = "victorgps.target"

    fun fromApp(): GpsProfileStore =
        GpsProfileStore(EncryptedPrefsSecretStore(App.get().getEncryptedPrefs()))
  }
}
