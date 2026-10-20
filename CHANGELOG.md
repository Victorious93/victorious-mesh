# Changelog

## Unreleased

### Changed
- Gradle `namespace` is `com.victorious.mesh` (`R` / `BuildConfig`). Kotlin
  sources stay `com.tailscale.ipn` (gomobile `libtailscale` + upstream-merge
  deferral; documented in README and MANUAL troubleshooting).
- Debug builds use `applicationIdSuffix ".debug"` so the debug package is
  `com.victorious.mesh.debug`. `applicationId` remains `com.victorious.mesh`.
  Fleet action `com.victorious.mesh.action.BRIDGE` is unchanged (not derived
  from `${applicationId}`).
- Versioning is inherited from upstream Tailscale (`mkversion` /
  `tailscale.version`) — an allowed fork exception, documented in README.

### Added
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
