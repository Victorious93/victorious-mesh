# Victorious Mesh

Victorious Mesh is a secure mesh-networking / VPN app for Android
(`com.victorious.mesh`). It connects your phone to Don's private network, run by a
self-hosted [Headscale](https://headscale.net) control server.

> **Testing status:** builds and JVM unit tests pass locally and in CI. Nothing has been
> tested on a real device or against a real Headscale server yet.

## What it is

- A Tailscale/WireGuard-based client. It is a fork of the open-source
  [tailscale-android](https://github.com/tailscale/tailscale-android) app
  (BSD-3-Clause + PATENTS; credited in `NOTICE`, see also `LICENSE`, `PATENTS`).
- It owns the **one** Android `VpnService` for the whole fleet. Android allows only one
  VPN at a time, so other fleet apps go through this one rather than running their own.
- Point it at your server in **Settings → Control server**, e.g.
  `https://headscale.example.com`. The server **must be HTTPS on port 443**. There is no
  default server.
- Log in interactively, or with a **pre-auth key**
  (`headscale preauthkeys create --user USER`) on the same screen.
- Diagnostic log upload to Tailscale is permanently disabled.
- ABIs: arm64-v8a and armeabi-v7a.

## Where it fits in the fleet

Victorious Mesh is an **EXTERNAL_NODE**. The fleet controller app
**droidcommand-AI** (`ai.droidcommand.app`) drives it:

- **Transport:** an Android Messenger bridge, intent action
  `com.victorious.mesh.action.BRIDGE`.
- **Protocol:** the shared fleet JSON envelope, `protocol_major` 1.
- **Pairing:** by package name + signing-certificate SHA-256, checked on this side.
  There is **no shared signing key**.
- **Allowlist today:** only the droidcommand-AI **debug** cert
  (`ai.droidcommand.app` and `ai.droidcommand.app.debug`). The release cert is pending
  and is not accepted until it's added (see `TODO(release cert)` in
  `android/src/main/java/com/tailscale/ipn/fleet/FleetProtocol.kt`).
- Each install has a permanent node id: `mesh-` + 32 lowercase hex characters.

### Operations

| Operation | What it does |
|---|---|
| `mesh.capabilities.get` | Lists supported operations (`dca_required: false`, `fleet_class: external_node`) |
| `mesh.status` | Connection state, control server URL, mesh IPs |
| `mesh.up` | Turns the mesh on (refused until the user has opened the app once and approved the VPN) |
| `mesh.down` | Turns the mesh off |
| `mesh.ip.get` | This phone's mesh IPv4 / IPv6 |

**External control is OFF by default.** To turn it on, open the app, go to **Settings**,
and switch on **Allow fleet controller (droidcommand-AI)**.
Full details, errors and examples: [docs/FLEET_BRIDGE.md](docs/FLEET_BRIDGE.md).

## Build and run

You need Go (version in `go.mod`), gomobile (the build script installs it), JDK 17+, Android SDK (platform 36, build-tools 36.0.0) and NDK r26.

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
./scripts/build-aar.sh                     # Go core -> android/libs/libtailscale.aar
cd android
./gradlew assembleDebug                    # APK in android/build/outputs/apk/debug/
./gradlew testDebugUnitTest                # JVM unit tests
```

Or skip the local build: every CI run uploads the debug APK as the
**`victorious-mesh-debug-apk`** artifact on the GitHub Actions run page.

## Signing

- **Debug:** a fixed, committed debug key, so every build (local and CI) has the same
  signature: `android/keystore/victorious-mesh-debug.jks`, alias `victoriousmeshdebug`,
  SHA-256
  `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4`.
- **Release:** the app's **own** release key, supplied only through CI secrets via
  `VM_RELEASE_STORE_FILE`, `VM_RELEASE_STORE_PASSWORD`, `VM_RELEASE_KEY_ALIAS` and
  `VM_RELEASE_KEY_PASSWORD`. It is not the shared fleet key and is never committed.
  (Whether those secrets are configured in the repo yet hasn't been checked: [Guessing].)
