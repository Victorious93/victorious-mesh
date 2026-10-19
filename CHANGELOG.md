# Changelog

## Unreleased

### Added
- **WireGuard tunnel mode.** Import a wg-quick `.conf` (file, paste or QR image), manage
  profiles (rename/delete), and connect a standard WireGuard tunnel instead of the mesh.
  Mesh and WireGuard are mutually exclusive on the app's single VpnService. Configs and
  keys are stored encrypted (EncryptedSharedPreferences / Android Keystore).
- Fleet bridge: `mesh.status` / `mesh.ip.get` report the mesh down while WireGuard mode
  is active, and `mesh.up` is refused in that state. No new operations.
