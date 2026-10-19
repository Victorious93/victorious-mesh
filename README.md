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
  Mesh does **not** share a signing key with the controller.
- **Allowlist:** `ai.droidcommand.app` and `ai.droidcommand.app.debug`, each pinned to
  two certs: the droidcommand-AI debug cert (`88:00:51:A6:…:A0:C8:4D`) and the shared
  fleet release cert the controller signs with (`9E:A7:B3:14:…:F7:2D:8D`). Full values in
  [docs/FLEET_BRIDGE.md](docs/FLEET_BRIDGE.md). Mesh's own signing keys are separate.
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

## WireGuard mode

Besides the mesh, the app can run a plain WireGuard tunnel from a standard
wg-quick `.conf` file (for example one from a VPN provider or your own server).

- **Open it:** Settings → **WireGuard**.
- **Add a profile:** **Import .conf** (system file picker), **Paste** the config text,
  or **QR image** (pick a screenshot/photo of a WireGuard QR code). Profiles can be
  renamed and deleted.
- **Connect / disconnect:** tap **Connect** on a profile. The card at the top shows the
  current tunnel mode (Mesh or WireGuard) and whether it is connected.
- **One tunnel at a time.** Android allows one VPN per app, so the modes are mutually
  exclusive. Connecting WireGuard turns the mesh off first; turning the mesh on (main
  screen) turns WireGuard off first. **Switch back to mesh mode** returns to the mesh.
- **What is used from the config:** `PrivateKey`, `Address`, `DNS` (IPs become DNS
  servers, names become search domains), `MTU` (default 1280), `ListenPort`, and per
  peer `PublicKey`, `PresharedKey`, `AllowedIPs` (installed as routes), `Endpoint`
  (host names are resolved when connecting) and `PersistentKeepalive`. Linux-only
  wg-quick keys (`PostUp`, `Table`, `FwMark`, ...) are ignored.
- **Keys:** the whole config, including the private key, is stored only in the app's
  EncryptedSharedPreferences (AES-256-GCM, master key in the Android Keystore). Keys are
  never logged and never included in error messages.
- **Fleet bridge:** no new operations. While WireGuard mode is active, `mesh.status`
  reports the mesh as down (`connected: false`, no mesh IPs) and `mesh.up` is refused
  until the user switches back to mesh mode in the app.
- **How it works:** WireGuard runs inside the app's existing Go core using
  `github.com/tailscale/wireguard-go` (MIT), on the same VpnService the mesh uses. No
  second Go runtime and no extra native library is added.

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
