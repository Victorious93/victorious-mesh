# Changelog

## Unreleased

### Changed
- Gradle `namespace` is `com.victorious.mesh` (`R` / `BuildConfig`). Kotlin
  sources stay `com.tailscale.ipn` (gomobile `libtailscale` + upstream-merge
  deferral; documented in README and MANUAL troubleshooting).
  `applicationId` remains `com.victorious.mesh` for debug and release.
- Versioning is inherited from upstream Tailscale (`mkversion` /
  `tailscale.version`) — an allowed fork exception, documented in README.
- `node.status.get` canonical path is a standalone `BridgeRequest` with v1.1
  envelope fields in `parameters` (auth / replay / expiry; no confirmation
  gating). `remote.command` `actionRef` is a compatible alias. Unknown param
  keys are ignored; only a malformed `args.nodeId` is `invalid_parameters`.

### Added
- **Auto-connect (Settings → Auto-connect).** Off by default. Starts
  the last mesh or WireGuard tunnel on app open and after boot when
  VPN permission is already granted. Android 12+ may block boot
  starts; Always-on VPN is documented as stronger. No new fleet ops.
  **[not device-verified]**
- **Kill switch (Settings → Kill switch).** Off by default. When the
  mesh or WireGuard is started, an unexpected drop holds a blocking
  VPN and reconnects. Explicit disconnect (main switch / tile) leaves.
  Android Always-on + “Block connections without VPN” is documented as
  stronger. No new fleet ops. **[not device-verified]**
- **Forced full-traffic upstream proxy (Settings → Proxy).** Off by
  default. TUN-to-SOCKS5/HTTP-CONNECT in libtailscale sends app TCP
  (and SOCKS5 UDP ASSOCIATE) through the chosen proxy with credentials.
  Fail-closed blocks those flows if the proxy drops. Mesh IPs stay on
  the mesh; the proxy host is excluded to prevent a routing loop. DNS
  and IPv6 gaps are documented. No new fleet ops. **[not device-verified]**
- **Per-app routing (Settings → Per-app routing).** Direct bypass via
  `addDisallowedApplication`. Exit node and upstream proxy choices are
  saved but cannot be applied per app on one VpnService (device-wide
  only). On-demand public IP/country check from a configurable endpoint
  (this app's path). No per-app traffic counters. No GPS spoofing. No
  new fleet ops. **[not device-verified]**
- **Proxy (Settings → Proxy).** Off by default. Share this phone as a
  mesh-bound SOCKS5 + HTTP CONNECT listener (userspace proxies in
  libtailscale; bind only this node's mesh IPv4, never 0.0.0.0 / Wi-Fi /
  cellular; optional EncryptedSharedPreferences auth; configurable port;
  persistent notification). Advisory HTTP *upstream* via
  `VpnService.Builder.setHttpProxy` (API 29+; apps may ignore it; no
  credential field). SOCKS5 upstream is saved only. Cannot promise no leak.
  No new fleet operations. **[not device-verified]**
- Per-caller fleet allowlist scope. Victorious Browser
  (`com.victorious.browser`, debug cert pinned; release TBD) may call only
  `mesh.status` and `mesh.ip` / `mesh.ip.get`. All other ops, including
  `node.status.get`, `config.*`, `mesh.up`/`mesh.down`, and every remote
  envelope `op`, return `unauthorized` / `unauthorized_caller`.
  droidcommand-AI stays full-access. Mesh IPs are never logged and must
  never appear in config backups (`FleetRedaction`).
- Fleet `config.export` / `config.import` (UNIFIED_BACKUP, droidcommand-AI
  #236): standalone `BridgeRequest` ops (not `remote.command` actionRefs)
  with v1.1 envelope fields in `parameters`. Advertised on `capabilities.get`
  as `remoteAllowed: false`. Export returns one `UnifiedBackupEntry` for
  `com.victorious.mesh` (control URL, tunnel mode, non-secret prefs, WireGuard
  profile metadata). Secret names refuse with `invalid_parameters`. Import
  supports `dryRun` and `conflictPolicy` skip / overwrite / merge-by-id;
  non-dryRun needs on-phone Approve/Deny; never enables external control.
- Fleet `node.status.get` (STATUS-TRAY): `NodeStatusSnapshot` with mesh / vpn /
  node_health. Advertised on `capabilities.get`. Same v1.1 envelope auth,
  replay, and expiry as remote control. Down services still answer `ok` with
  `state: down`. WireGuard mode reports mesh down and vpn up.
- **WireGuard tunnel mode.** Import a wg-quick `.conf` (file, paste or QR image), manage
  profiles (rename/delete), and connect a standard WireGuard tunnel instead of the mesh.
  Mesh and WireGuard are mutually exclusive on the app's single VpnService. Configs and
  keys are stored encrypted (EncryptedSharedPreferences / Android Keystore).
  Full-tunnel configs without DNS get a public resolver; endpoint IPs are excluded
  from the VPN routes on Android 13+.
- Fleet bridge: `mesh.status` / `mesh.ip.get` report the mesh down while WireGuard mode
  is active, and `mesh.up` is refused in that state. No new operations.
- Fleet bridge remote-control envelope v1.1 (`BRIDGE_PROTOCOL_MAJOR=1`, `MINOR=1`):
  `capabilities.get`, `remote.command`, `macro.step`, `macro.run` on the existing
  Messenger transport. Replay/expiry/nonce cache, destructive `mesh.down` confirmation
  on the phone, and `trigger.*` never authorizes. Legacy `mesh.*` ops stay compatible.
- Fleet envelope v1.1 follow-up (ratified): expiry is `now > timestampMs+ttlMs` or
  future skew `timestampMs > now+120s` only (full 300s TTL is usable). `targetNodeRef`
  blank/malformed → `invalid_parameters`, unknown → `not_found`, known but unpaired →
  `not_paired`. CamelCase envelope + snake_case wrapper unchanged.
