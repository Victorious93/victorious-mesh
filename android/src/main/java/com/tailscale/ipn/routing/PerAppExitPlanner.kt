// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.routing

import com.tailscale.ipn.proxy.SavedProxy
import com.tailscale.ipn.proxy.SavedProxyPlanner
import com.tailscale.ipn.proxy.UpstreamKind
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the Go side does with one app's flows. Wire names match libtailscale/perapp. */
enum class PerAppAction(val wire: String) {
  /** Device-wide path (forced proxy, exit node, or direct internet). */
  DEFAULT("default"),
  /** Mesh engine → the phone's current exit node (skips a forced proxy). */
  ENGINE("engine"),
  /** This app's own saved proxy. */
  PROXY("proxy"),
  /** Dropped: the chosen path is not available and fail-closed is on. */
  BLOCK("block"),
  /** Excluded from the VPN by Android; never reaches the Go side. */
  BYPASS("bypass"),
}

data class ResolvedAppRoute(
    val pkg: String,
    val requested: AppRouteKind,
    val action: PerAppAction,
    val proxy: SavedProxy? = null,
    /** Plain-language status shown under the app. */
    val reason: String,
    /** True when the app is not getting what it asked for. */
    val warning: Boolean = false,
)

data class PerAppRule(val uid: Int, val route: ResolvedAppRoute)

/**
 * True per-app exit node / proxy choice. One Android VPN has one tunnel,
 * so the Go side finds the app for every new connection
 * (ConnectivityManager.getConnectionOwnerUid, Android 10+) and sends it
 * down that app's path. Wireguard picks a peer by destination, so only
 * ONE exit node can be active per phone: an app may pick that exit node
 * (even while a forced proxy is on), but a different exit node per app
 * cannot be carried; such apps are blocked (fail-closed) or fall back.
 */
object PerAppExitPlanner {
  /** getConnectionOwnerUid needs Android 10 (API 29). */
  const val MIN_SDK = 29

  fun supported(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

  fun resolve(
      route: AppRoute,
      enforce: Boolean,
      failClosed: Boolean,
      deviceExitNodeId: String?,
      proxies: List<SavedProxy>,
      sdkInt: Int,
      exitNodeLabel: (String) -> String = { it },
  ): ResolvedAppRoute {
    if (route.kind == AppRouteKind.DIRECT) {
      return ResolvedAppRoute(route.pkg, route.kind, PerAppAction.BYPASS, reason = "bypasses the VPN")
    }
    if (!enforce || !supported(sdkInt)) {
      val legacy = PerAppPlanner.applied(route)
      val why =
          if (enforce && !supported(sdkInt) && route.kind != AppRouteKind.MESH)
              "needs Android 10 or newer; ${legacy.reason}"
          else legacy.reason
      return ResolvedAppRoute(
          route.pkg,
          route.kind,
          PerAppAction.DEFAULT,
          reason = why,
          warning = route.kind == AppRouteKind.EXIT_NODE || route.kind == AppRouteKind.UPSTREAM_PROXY,
      )
    }
    val unavailable = { why: String ->
      if (failClosed)
          ResolvedAppRoute(route.pkg, route.kind, PerAppAction.BLOCK, reason = "blocked: $why", warning = true)
      else
          ResolvedAppRoute(
              route.pkg,
              route.kind,
              PerAppAction.DEFAULT,
              reason = "uses the normal route: $why",
              warning = true,
          )
    }
    return when (route.kind) {
      AppRouteKind.DIRECT -> error("handled above")
      AppRouteKind.MESH ->
          ResolvedAppRoute(route.pkg, route.kind, PerAppAction.DEFAULT, reason = "uses the phone's normal route")
      AppRouteKind.EXIT_NODE -> {
        val want = route.exitNodeId
        when {
          want.isEmpty() -> unavailable("no exit node picked")
          deviceExitNodeId.isNullOrEmpty() ->
              unavailable("${exitNodeLabel(want)} is not the phone's exit node (none is on)")
          want != deviceExitNodeId ->
              unavailable("${exitNodeLabel(want)} is not the phone's current exit node; only one can be on at a time")
          else ->
              ResolvedAppRoute(
                  route.pkg,
                  route.kind,
                  PerAppAction.ENGINE,
                  reason = "goes out through ${exitNodeLabel(want)}",
              )
        }
      }
      AppRouteKind.UPSTREAM_PROXY -> {
        val id = route.proxyId.ifEmpty { com.tailscale.ipn.proxy.MAIN_UPSTREAM_PROXY_ID }
        val proxy = proxies.firstOrNull { it.id == id } ?: return unavailable("that proxy is not saved any more")
        val problem = SavedProxyPlanner.problem(proxy)
        if (problem != null) return unavailable("proxy ${proxy.name}: $problem")
        val down = if (failClosed) "; blocked if it is down" else "; goes direct if it is down"
        ResolvedAppRoute(
            route.pkg,
            route.kind,
            PerAppAction.PROXY,
            proxy = proxy,
            reason = "goes through ${proxy.name}$down",
        )
      }
    }
  }

  /** Apps that must stay inside the tunnel for their choice to work. */
  fun capturedPackages(resolved: List<ResolvedAppRoute>): List<String> =
      resolved
          .filter {
            it.action == PerAppAction.ENGINE || it.action == PerAppAction.PROXY || it.action == PerAppAction.BLOCK
          }
          .map { it.pkg }
          .distinct()

  /** JSON for Libtailscale.setPerAppPolicy. Credentials are included; never log it. */
  fun policyJson(
      enabled: Boolean,
      exitNodeActive: Boolean,
      failClosed: Boolean,
      rules: List<PerAppRule>,
  ): String =
      buildJsonObject {
            put("enabled", enabled)
            put("exitNodeActive", exitNodeActive)
            putJsonArray("rules") {
              rules
                  .filter { it.route.action != PerAppAction.BYPASS && it.route.action != PerAppAction.DEFAULT }
                  .forEach { rule ->
                    addJsonObject {
                      put("uid", rule.uid)
                      put("action", rule.route.action.wire)
                      val p = rule.route.proxy
                      if (rule.route.action == PerAppAction.PROXY && p != null) {
                        putJsonObject("proxy") {
                          put("kind", if (p.kind == UpstreamKind.HTTP) "http" else "socks5")
                          put("host", p.host.trim())
                          put("port", p.port)
                          if (p.username.isNotEmpty()) put("username", p.username)
                          if (p.password.isNotEmpty()) put("password", p.password)
                          put("failClosed", failClosed)
                        }
                      }
                    }
                  }
            }
          }
          .toString()
}
