package com.tailscale.ipn.fleet.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemePlannerTest {
  private val launchPkg = FleetThemeContract.PACKAGE_LAUNCH
  private val launchCert = FleetThemeContract.LAUNCH_DEBUG_CERT

  private fun pinnedQuery(
      schemaVersion: Int? = 1,
      json: String? = neonJson(),
      columns: Map<String, String> = emptyMap(),
      securityException: Boolean = false,
      providerMissing: Boolean = false,
      providerPackage: String? = launchPkg,
      providerCerts: List<String> = listOf(launchCert),
  ) =
      FleetThemeQuery(
          providerPackage = providerPackage,
          providerCerts = providerCerts,
          securityException = securityException,
          providerMissing = providerMissing,
          schemaVersion = schemaVersion,
          json = json,
          columns = columns,
          updatedAtMs = 42L,
      )

  private fun neonJson(schema: Int = 1) =
      """
      {
        "schemaVersion": $schema,
        "mode": "NEON_FLEET",
        "iconStyle": "SYSTEM",
        "iconPackPackage": "",
        "updatedAtMs": 42,
        "palette": {
          "background": "#FF0B0C12",
          "surface": "#FF161821",
          "primary": "#FF00E5FF",
          "secondary": "#FFE840FF",
          "onBackground": "#FFF5F7FF",
          "muted": "#FF9AA3B5",
          "danger": "#FFFF6B8A",
          "stale": "#FFFFC14D",
          "tile": "#CC1A1D2B"
        }
      }
      """
          .trimIndent()

  @Test
  fun contractConstantsMatchSpec() {
    assertEquals(1, FleetThemeContract.SCHEMA_VERSION)
    assertEquals("com.victorious.launch.theme", FleetThemeContract.AUTHORITY)
    assertEquals("com.victorious.launch.action.FLEET_THEME_CHANGED", FleetThemeContract.ACTION_CHANGED)
    assertEquals("content://com.victorious.launch.theme/current", FleetThemeContract.URI)
    assertEquals("com.victorious.launch", FleetThemeContract.PACKAGE_LAUNCH)
  }

  @Test
  fun debugLaunchCertIsPinned() {
    assertTrue(FleetThemePlanner.providerTrusted(launchPkg, listOf(launchCert)))
    assertTrue(
        FleetThemePlanner.providerTrusted(
            launchPkg,
            listOf(FleetThemePlanner.normalize(launchCert)),
        )
    )
  }

  @Test
  fun wrongPackageIsRejectedEvenWithLaunchCert() {
    assertFalse(FleetThemePlanner.providerTrusted("com.evil.launch", listOf(launchCert)))
    assertFalse(FleetThemePlanner.providerTrusted("ai.droidcommand.app", listOf(launchCert)))
  }

  @Test
  fun wrongCertIsRejected() {
    assertFalse(FleetThemePlanner.providerTrusted(launchPkg, listOf("00".repeat(32))))
    assertFalse(FleetThemePlanner.providerTrusted(launchPkg, emptyList()))
    assertFalse(FleetThemePlanner.providerTrusted(launchPkg, listOf("TBD")))
    assertFalse(FleetThemePlanner.providerTrusted(null, listOf(launchCert)))
  }

  @Test
  fun securityExceptionFallsBack() {
    val d = FleetThemePlanner.decide(pinnedQuery(securityException = true))
    assertEquals(
        FleetThemeFallbackReason.SECURITY_EXCEPTION,
        (d as FleetThemeDecision.Fallback).reason,
    )
    assertEquals(
        FleetThemeSource.BUILTIN_NEON,
        FleetThemePlanner.snapshotOrBuiltin(d).source,
    )
  }

  @Test
  fun unpinnedProviderFallsBackEvenWithValidJson() {
    val d =
        FleetThemePlanner.decide(
            pinnedQuery(providerPackage = "com.evil.launch", providerCerts = listOf(launchCert))
        )
    assertEquals(FleetThemeFallbackReason.NOT_PINNED, (d as FleetThemeDecision.Fallback).reason)
  }

  @Test
  fun missingProviderFallsBack() {
    val d = FleetThemePlanner.decide(pinnedQuery(providerMissing = true, providerPackage = null))
    assertEquals(
        FleetThemeFallbackReason.PROVIDER_MISSING,
        (d as FleetThemeDecision.Fallback).reason,
    )
  }

  @Test
  fun schemaTwoIsRejected() {
    val d = FleetThemePlanner.decide(pinnedQuery(schemaVersion = 2, json = neonJson(2)))
    assertEquals(
        FleetThemeFallbackReason.UNSUPPORTED_SCHEMA,
        (d as FleetThemeDecision.Fallback).reason,
    )
  }

  @Test
  fun jsonSchemaWinsOverColumnSchema() {
    val d =
        FleetThemePlanner.decide(
            pinnedQuery(schemaVersion = 1, json = neonJson(2))
        )
    assertEquals(
        FleetThemeFallbackReason.UNSUPPORTED_SCHEMA,
        (d as FleetThemeDecision.Fallback).reason,
    )
  }

  @Test
  fun schemaZeroIsRejected() {
    val d = FleetThemePlanner.decide(pinnedQuery(schemaVersion = 0, json = neonJson(0)))
    assertEquals(
        FleetThemeFallbackReason.UNSUPPORTED_SCHEMA,
        (d as FleetThemeDecision.Fallback).reason,
    )
  }

  @Test
  fun decodeJsonRejectsUnknownSchema() {
    assertNull(FleetThemeCodec.decodeJson("""{"schemaVersion":2,"mode":"DARK"}"""))
    assertNull(FleetThemeCodec.decodeJson("""{"mode":"DARK"}"""))
    assertNull(FleetThemeCodec.decodeJson("not-json"))
  }

  @Test
  fun decodeJsonAcceptsSchemaOneAndIgnoresUnknownKeys() {
    val snap =
        FleetThemeCodec.decodeJson(
            """
            {
              "schemaVersion": 1,
              "mode": "LIGHT",
              "iconStyle": "MONOCHROME",
              "iconPackPackage": "com.example.pack",
              "updatedAtMs": 9,
              "futureField": true,
              "palette": {
                "primary": "#00838F",
                "mystery": "#FFFFFFFF"
              }
            }
            """
                .trimIndent()
        )
    requireNotNull(snap)
    assertEquals(1, snap.schemaVersion)
    assertEquals(FleetThemeMode.LIGHT, snap.mode)
    assertEquals(FleetIconStyle.MONOCHROME, snap.iconStyle)
    assertEquals("com.example.pack", snap.iconPackPackage)
    assertEquals("#00838F", snap.palette.primary)
    assertEquals(FleetThemePalettes.NEON.background, snap.palette.background)
    assertEquals(FleetThemeSource.FLEET, snap.source)
  }

  @Test
  fun pinnedSchemaOneApplies() {
    val d = FleetThemePlanner.decide(pinnedQuery())
    val snap = (d as FleetThemeDecision.Applied).snapshot
    assertEquals(FleetThemeSource.FLEET, snap.source)
    assertEquals(FleetThemeMode.NEON_FLEET, snap.mode)
    assertEquals("#FF00E5FF", snap.palette.primary)
  }

  @Test
  fun columnsWorkWhenJsonMissing() {
    val d =
        FleetThemePlanner.decide(
            pinnedQuery(
                json = null,
                columns =
                    mapOf(
                        FleetThemeContract.COL_MODE to "DARK",
                        FleetThemeContract.COL_PRIMARY to "#FF80DEEA",
                        "futureColumn" to "x",
                    ),
            )
        )
    val snap = (d as FleetThemeDecision.Applied).snapshot
    assertEquals(FleetThemeMode.DARK, snap.mode)
    assertEquals("#FF80DEEA", snap.palette.primary)
  }

  @Test
  fun parseArgbAcceptsRgbAndArgb() {
    assertEquals(0xFF00E5FFL, FleetThemePalettes.parseArgb("#00E5FF"))
    assertEquals(0xCC1A1D2BL, FleetThemePalettes.parseArgb("#CC1A1D2B"))
    assertNull(FleetThemePalettes.parseArgb("#XYZ"))
    assertNull(FleetThemePalettes.parseArgb("#1234"))
  }

  @Test
  fun extrasDoNotAuthorize() {
    val d =
        FleetThemePlanner.decide(
            pinnedQuery(
                providerPackage = "com.evil.app",
                providerCerts = listOf("AA:BB"),
                json = neonJson(),
            )
        )
    assertTrue(d is FleetThemeDecision.Fallback)
  }

  @Test
  fun registerRetryIsCapped() {
    org.junit.Assert.assertTrue(FleetThemePlanner.shouldRetryRegister(1, 5))
    org.junit.Assert.assertTrue(FleetThemePlanner.shouldRetryRegister(5, 5))
    org.junit.Assert.assertFalse(FleetThemePlanner.shouldRetryRegister(6, 5))
  }
}
