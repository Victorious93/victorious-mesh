# Victorious Mesh

Android mesh-VPN client for a self-hosted [Headscale](https://headscale.net)
control server. Based on the open-source
[tailscale-android](https://github.com/tailscale/tailscale-android) client
(BSD-3-Clause + PATENTS, see `LICENSE`, `PATENTS`, `NOTICE`).

- applicationId: `com.victorious.mesh`
- Control server: set it in **Settings -> Control server** (e.g.
  `https://headscale.example.com`). It **must be HTTPS on port 443**. There is
  no default server.
- Log in interactively, or with a pre-auth key
  (`headscale preauthkeys create --user USER`) from the same screen.
- Diagnostic log upload to Tailscale is permanently disabled.
- ABIs: arm64-v8a and armeabi-v7a only.

## Build

Requirements: Go (version in `go.mod`), JDK 17+, Android SDK (platform 36,
build-tools 36.0.0) and an Android NDK (r26 tested).

```sh
export ANDROID_HOME=/path/to/android-sdk
./scripts/build-aar.sh                 # Go core -> android/libs/libtailscale.aar
cd android && ./gradlew assembleDebug  # APK in android/build/outputs/apk/debug/
```

Debug builds are signed with the committed debug keystore
`android/keystore/victorious-mesh-debug.jks` (alias `victoriousmeshdebug`).
Release builds are signed only if `VM_RELEASE_STORE_FILE`,
`VM_RELEASE_STORE_PASSWORD`, `VM_RELEASE_KEY_ALIAS`, `VM_RELEASE_KEY_PASSWORD`
are set. Never commit a release key.

## TODO

- Fleet IPC Messenger service (not part of phase 1).
