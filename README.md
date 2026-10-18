# Victorious Mesh

Android app (`com.victorious.mesh`) that will be the **single owner of the fleet's `VpnService`**.

## Purpose

- Android allows only one active `VpnService` at a time. Victorious Mesh owns it for the whole
  droidcommand-AI fleet, so no other fleet app has to (or should) run its own VPN.
- It will act as a **Tailscale / WireGuard client** connecting the device to Don's
  **self-hosted Headscale** control server.
- In the droidcommand-AI fleet protocol it registers as an **`EXTERNAL_NODE`**. The fleet pairs
  external nodes by **package name + signing certificate**, so the debug cert below is fixed and
  shared by all debug builds (local and CI).

## Status

Scaffold only. A Kotlin + Jetpack Compose app with one `MainActivity` showing a neon status
screen (dark charcoal background, cyan-to-magenta glowing ring, `Mesh: not connected`).

**The VPN layer is not built yet.** It is pending research on embedding Tailscale vs. a plain
WireGuard (`wireguard-android` / `GoBackend`) client against Headscale.

## Toolchain

| Component | Version |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.13.0 |
| Kotlin (+ Compose compiler plugin) | 2.1.20 |
| Compose BOM | 2025.10.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| JDK | 17 |

## Build

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml`) builds the debug APK on every push and PR to `main` and
uploads it as the `victorious-mesh-debug-apk` artifact.

## Signing

### Debug (committed, fixed)

| Field | Value |
|---|---|
| Keystore | `app/keystore/victorious-mesh-debug.jks` (JKS) |
| Alias | `victoriousmeshdebug` |
| Store / key password | `android` |
| Cert SHA-256 | `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4` |
| Cert SHA-1 | `FE:F9:27:BD:BC:DD:7D:2E:D7:C0:D4:19:41:E9:0E:A2:4E:8B:57:48` |

This keystore exists only so every debug build has the same identity for fleet pairing.
It is **not** a secret and must never be used for release.

### Release (never committed)

No release keystore is in this repo. A `release` signing config is created only when these
environment variables (e.g. CI secrets) are set:

- `VM_RELEASE_STORE_FILE`
- `VM_RELEASE_STORE_PASSWORD`
- `VM_RELEASE_KEY_ALIAS`
- `VM_RELEASE_KEY_PASSWORD`
