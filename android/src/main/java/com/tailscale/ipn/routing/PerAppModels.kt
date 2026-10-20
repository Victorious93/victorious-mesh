// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.routing

import com.tailscale.ipn.wireguard.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class AppRouteKind {
  DIRECT,
  MESH,
  EXIT_NODE,
  UPSTREAM_PROXY,
}

@Serializable
data class AppRoute(
    val pkg: String,
    val kind: AppRouteKind = AppRouteKind.MESH,
    val exitNodeId: String = "",
) {
  override fun toString(): String = "AppRoute(pkg=$pkg, kind=$kind, exitNodeId=${exitNodeId.isNotEmpty()})"
}

data class VpnPackagePlan(
    val packages: List<String>,
    val allowPackages: Boolean,
    val reason: String,
)

data class AppliedRoute(
    val pkg: String,
    val requested: AppRouteKind,
    val applied: AppRouteKind,
    val reason: String,
)

data class PublicSighting(
    val ip: String?,
    val country: String?,
    val rawPreview: String,
)

const val DEFAULT_SIGHTING_ENDPOINT = "https://ipinfo.io/json"
const val ROUTES_KEY = "perapp.routes"
const val ENDPOINT_KEY = "perapp.sighting.endpoint"

class PerAppStore(private val store: SecretStore) {
  private val json = Json { ignoreUnknownKeys = true }

  fun routes(): List<AppRoute> {
    val raw = store.get(ROUTES_KEY) ?: return emptyList()
    return runCatching { json.decodeFromString(ListSerializer(AppRoute.serializer()), raw) }
        .getOrDefault(emptyList())
  }

  fun saveRoutes(routes: List<AppRoute>) {
    val kept = routes.filter { it.kind != AppRouteKind.MESH || it.exitNodeId.isNotEmpty() }
    store.put(ROUTES_KEY, json.encodeToString(ListSerializer(AppRoute.serializer()), kept))
  }

  fun setRoute(route: AppRoute) {
    val next = routes().filterNot { it.pkg == route.pkg } + route
    saveRoutes(next)
  }

  fun routeFor(pkg: String): AppRoute = routes().firstOrNull { it.pkg == pkg } ?: AppRoute(pkg)

  fun sightingEndpoint(): String = store.get(ENDPOINT_KEY)?.ifBlank { null } ?: DEFAULT_SIGHTING_ENDPOINT

  fun setSightingEndpoint(url: String) {
    store.put(ENDPOINT_KEY, url.trim().ifBlank { DEFAULT_SIGHTING_ENDPOINT })
  }
}

object PerAppPlanner {
  /**
   * One VpnService cannot pin different exit nodes or upstream proxies per app.
   * DIRECT is the only per-app path (exclude from the tunnel). Everything else
   * rides the single mesh/exit/proxy of the device.
   */
  fun applied(route: AppRoute): AppliedRoute =
      when (route.kind) {
        AppRouteKind.DIRECT ->
            AppliedRoute(route.pkg, route.kind, AppRouteKind.DIRECT, "bypasses the VPN")
        AppRouteKind.MESH ->
            AppliedRoute(route.pkg, route.kind, AppRouteKind.MESH, "uses the device mesh tunnel")
        AppRouteKind.EXIT_NODE ->
            AppliedRoute(
                route.pkg,
                route.kind,
                AppRouteKind.MESH,
                "exit node is device-wide; this app uses the VPN like mesh-only",
            )
        AppRouteKind.UPSTREAM_PROXY ->
            AppliedRoute(
                route.pkg,
                route.kind,
                AppRouteKind.MESH,
                "upstream proxy is device-wide; this app uses the VPN like mesh-only",
            )
      }

  fun directPackages(routes: List<AppRoute>): List<String> =
      routes.filter { applied(it).applied == AppRouteKind.DIRECT }.map { it.pkg }.distinct()

  fun mergeSplitTunnel(
      splitSelected: List<String>,
      allowSelected: Boolean,
      directPackages: List<String>,
  ): VpnPackagePlan {
    val direct = directPackages.filter { it.isNotBlank() }.distinct()
    return if (allowSelected) {
      VpnPackagePlan(
          packages = (splitSelected - direct.toSet()).distinct(),
          allowPackages = true,
          reason = "include-mode split tunnel minus Direct apps",
      )
    } else {
      VpnPackagePlan(
          packages = (splitSelected + direct).distinct(),
          allowPackages = false,
          reason = "exclude-mode: Direct apps skip the VPN",
      )
    }
  }
}

object IpSightingParser {
  private val IP = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
  private val QUOTED = Regex(""""(ip|query|origin)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
  private val COUNTRY =
      Regex(""""(country|countryCode|country_code)"\s*:\s*"([A-Za-z]{2,})"""", RegexOption.IGNORE_CASE)

  fun parse(body: String): PublicSighting {
    val ip =
        QUOTED.find(body)?.groupValues?.getOrNull(2)
            ?: IP.find(body)?.value
    val country = COUNTRY.find(body)?.groupValues?.getOrNull(2)
    val preview = body.trim().replace("\n", " ").take(160)
    return PublicSighting(ip = ip, country = country, rawPreview = preview)
  }

  fun looksLikeMeshIPv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 100 && b in 64..127
  }
}
