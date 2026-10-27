// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.fleet.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * FLEET_THEME schemaVersion 1 client contract (victorlaunch #14 @ 555d24b).
 *
 * VictorLaunch is the only writer. This app only reads. Never treat a
 * payload field as auth — pin the provider package + cert.
 */
object FleetThemeContract {
  const val SCHEMA_VERSION = 1
  const val AUTHORITY = "com.victorious.launch.theme"
  const val PATH_CURRENT = "current"
  const val URI = "content://$AUTHORITY/$PATH_CURRENT"
  const val MIME = "vnd.android.cursor.item/vnd.com.victorious.launch.theme.current"
  const val PERMISSION = "com.victorious.launch.permission.READ_FLEET_THEME"
  const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"
  const val EXTRA_SCHEMA_VERSION = "schemaVersion"
  const val EXTRA_MODE = "mode"
  const val EXTRA_ICON_STYLE = "iconStyle"
  const val EXTRA_UPDATED_AT = "updatedAtMs"

  const val COL_SCHEMA_VERSION = "schemaVersion"
  const val COL_MODE = "mode"
  const val COL_ICON_STYLE = "iconStyle"
  const val COL_ICON_PACK = "iconPackPackage"
  const val COL_JSON = "json"
  const val COL_UPDATED_AT = "updatedAtMs"
  const val COL_BACKGROUND = "background"
  const val COL_SURFACE = "surface"
  const val COL_PRIMARY = "primary"
  const val COL_SECONDARY = "secondary"
  const val COL_ON_BACKGROUND = "onBackground"
  const val COL_MUTED = "muted"
  const val COL_DANGER = "danger"
  const val COL_STALE = "stale"
  const val COL_TILE = "tile"

  const val PACKAGE_LAUNCH = "com.victorious.launch"

  /** VictorLaunch debug cert (FleetPins.VICTORLAUNCH_DEBUG). Release TBD — not a pin. */
  const val LAUNCH_DEBUG_CERT =
      "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69"
}

enum class FleetThemeMode {
  LIGHT,
  DARK,
  NEON_FLEET,
  MATERIAL_YOU,
  ;

  companion object {
    fun parse(raw: String?): FleetThemeMode {
      val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return NEON_FLEET
      return entries.firstOrNull { it.name == key } ?: NEON_FLEET
    }
  }
}

enum class FleetIconStyle {
  SYSTEM,
  MONOCHROME,
  TINTED,
  ICON_PACK,
  ;

  companion object {
    fun parse(raw: String?): FleetIconStyle {
      val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return SYSTEM
      return entries.firstOrNull { it.name == key } ?: SYSTEM
    }
  }
}

enum class FleetThemeSource {
  BUILTIN_NEON,
  FLEET,
}

enum class FleetThemeFallbackReason {
  PROVIDER_MISSING,
  NOT_PINNED,
  SECURITY_EXCEPTION,
  UNSUPPORTED_SCHEMA,
  INVALID_PAYLOAD,
}

data class FleetPalette(
    val background: String,
    val surface: String,
    val primary: String,
    val secondary: String,
    val onBackground: String,
    val muted: String,
    val danger: String,
    val stale: String,
    val tile: String,
)

data class FleetThemeSnapshot(
    val schemaVersion: Int = FleetThemeContract.SCHEMA_VERSION,
    val mode: FleetThemeMode,
    val iconStyle: FleetIconStyle,
    val iconPackPackage: String?,
    val palette: FleetPalette,
    val updatedAtMs: Long,
    val source: FleetThemeSource,
)

data class FleetThemeQuery(
    val providerPackage: String?,
    val providerCerts: List<String>,
    val securityException: Boolean = false,
    val providerMissing: Boolean = false,
    val schemaVersion: Int? = null,
    val json: String? = null,
    val columns: Map<String, String> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

sealed class FleetThemeDecision {
  data class Applied(val snapshot: FleetThemeSnapshot) : FleetThemeDecision()

  data class Fallback(val reason: FleetThemeFallbackReason) : FleetThemeDecision()
}

object FleetThemePalettes {
  val NEON =
      FleetPalette(
          background = "#FF0B0C12",
          surface = "#FF161821",
          primary = "#FF00E5FF",
          secondary = "#FFE840FF",
          onBackground = "#FFF5F7FF",
          muted = "#FF9AA3B5",
          danger = "#FFFF6B8A",
          stale = "#FFFFC14D",
          tile = "#CC1A1D2B",
      )

  fun builtinNeonSnapshot(): FleetThemeSnapshot =
      FleetThemeSnapshot(
          schemaVersion = FleetThemeContract.SCHEMA_VERSION,
          mode = FleetThemeMode.NEON_FLEET,
          iconStyle = FleetIconStyle.SYSTEM,
          iconPackPackage = null,
          palette = NEON,
          updatedAtMs = 0L,
          source = FleetThemeSource.BUILTIN_NEON,
      )

  fun parseArgb(hex: String): Long? {
    val h = hex.trim().removePrefix("#")
    val value = h.toLongOrNull(16) ?: return null
    return when (h.length) {
      6 -> 0xFF000000L or value
      8 -> value
      else -> null
    }
  }
}

object FleetThemeCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun decodeJson(text: String): FleetThemeSnapshot? {
    return try {
      val obj = json.parseToJsonElement(text).jsonObject
      val schema =
          obj["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
      if (schema != FleetThemeContract.SCHEMA_VERSION) return null
      val paletteObj = obj["palette"]?.jsonObject ?: JsonObject(emptyMap())
      FleetThemeSnapshot(
          schemaVersion = schema,
          mode = FleetThemeMode.parse(obj["mode"]?.jsonPrimitive?.contentOrNull),
          iconStyle = FleetIconStyle.parse(obj["iconStyle"]?.jsonPrimitive?.contentOrNull),
          iconPackPackage =
              obj["iconPackPackage"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
          palette =
              paletteFrom { key, fallback ->
                hexOrNeon(paletteObj[key]?.jsonPrimitive?.contentOrNull, fallback)
              },
          updatedAtMs = obj["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
          source = FleetThemeSource.FLEET,
      )
    } catch (_: Exception) {
      null
    }
  }

  fun decodeColumns(schemaVersion: Int?, columns: Map<String, String>, updatedAtMs: Long):
      FleetThemeSnapshot? {
    if (schemaVersion != FleetThemeContract.SCHEMA_VERSION) return null
    return FleetThemeSnapshot(
        schemaVersion = schemaVersion,
        mode = FleetThemeMode.parse(columns[FleetThemeContract.COL_MODE]),
        iconStyle = FleetIconStyle.parse(columns[FleetThemeContract.COL_ICON_STYLE]),
        iconPackPackage = columns[FleetThemeContract.COL_ICON_PACK]?.ifBlank { null },
        palette = paletteFrom { key, fallback ->
          hexOrNeon(columns[key], fallback)
        },
        updatedAtMs = updatedAtMs,
        source = FleetThemeSource.FLEET,
    )
  }

  private fun hexOrNeon(raw: String?, fallback: String): String {
    val value = raw?.trim()?.ifBlank { null } ?: return fallback
    return if (FleetThemePalettes.parseArgb(value) != null) value else fallback
  }

  private fun paletteFrom(hex: (String, String) -> String): FleetPalette {
    val neon = FleetThemePalettes.NEON
    return FleetPalette(
        background = hex(FleetThemeContract.COL_BACKGROUND, neon.background),
        surface = hex(FleetThemeContract.COL_SURFACE, neon.surface),
        primary = hex(FleetThemeContract.COL_PRIMARY, neon.primary),
        secondary = hex(FleetThemeContract.COL_SECONDARY, neon.secondary),
        onBackground = hex(FleetThemeContract.COL_ON_BACKGROUND, neon.onBackground),
        muted = hex(FleetThemeContract.COL_MUTED, neon.muted),
        danger = hex(FleetThemeContract.COL_DANGER, neon.danger),
        stale = hex(FleetThemeContract.COL_STALE, neon.stale),
        tile = hex(FleetThemeContract.COL_TILE, neon.tile),
    )
  }
}

object FleetThemePlanner {
  fun normalize(fp: String) = fp.replace(":", "").replace(" ", "").lowercase()

  fun pinnedLaunchCerts(): Set<String> =
      setOf(normalize(FleetThemeContract.LAUNCH_DEBUG_CERT))

  fun providerTrusted(packageName: String?, certs: List<String>): Boolean {
    if (packageName != FleetThemeContract.PACKAGE_LAUNCH) return false
    if (certs.isEmpty()) return false
    val incoming = certs.map { normalize(it) }.toSet()
    return incoming.any { it in pinnedLaunchCerts() }
  }

  fun decide(query: FleetThemeQuery): FleetThemeDecision {
    if (query.securityException) {
      return FleetThemeDecision.Fallback(FleetThemeFallbackReason.SECURITY_EXCEPTION)
    }
    if (query.providerMissing || query.providerPackage.isNullOrBlank()) {
      return FleetThemeDecision.Fallback(FleetThemeFallbackReason.PROVIDER_MISSING)
    }
    if (!providerTrusted(query.providerPackage, query.providerCerts)) {
      return FleetThemeDecision.Fallback(FleetThemeFallbackReason.NOT_PINNED)
    }
    val jsonSchema = schemaFromJson(query.json)
    if (jsonSchema != null && jsonSchema != FleetThemeContract.SCHEMA_VERSION) {
      return FleetThemeDecision.Fallback(FleetThemeFallbackReason.UNSUPPORTED_SCHEMA)
    }
    val schema = query.schemaVersion ?: jsonSchema
    if (schema != null && schema != FleetThemeContract.SCHEMA_VERSION) {
      return FleetThemeDecision.Fallback(FleetThemeFallbackReason.UNSUPPORTED_SCHEMA)
    }
    val snapshot =
        query.json?.let { FleetThemeCodec.decodeJson(it) }
            ?: FleetThemeCodec.decodeColumns(schema, query.columns, query.updatedAtMs)
    if (snapshot == null) {
      return FleetThemeDecision.Fallback(
          if (schema != null && schema != FleetThemeContract.SCHEMA_VERSION) {
            FleetThemeFallbackReason.UNSUPPORTED_SCHEMA
          } else {
            FleetThemeFallbackReason.INVALID_PAYLOAD
          }
      )
    }
    return FleetThemeDecision.Applied(snapshot)
  }

  fun snapshotOrBuiltin(decision: FleetThemeDecision): FleetThemeSnapshot =
      when (decision) {
        is FleetThemeDecision.Applied -> decision.snapshot
        is FleetThemeDecision.Fallback -> FleetThemePalettes.builtinNeonSnapshot()
      }

  private fun schemaFromJson(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    return try {
      Json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.contentOrNull
          ?.toIntOrNull()
    } catch (_: Exception) {
      null
    }
  }
}
