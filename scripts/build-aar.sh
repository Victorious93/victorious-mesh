#!/usr/bin/env bash
# Victorious Mesh: build android/libs/libtailscale.aar with a stock Go toolchain
# (no Tailscale Go fork), restricted to arm64-v8a + armeabi-v7a to keep size down.
# Requires: Go (>= version in go.mod), ANDROID_HOME, and an NDK (ANDROID_NDK_HOME
# or the newest one under $ANDROID_HOME/ndk).
set -euo pipefail
cd "$(dirname "$0")/.."
export GOTOOLCHAIN=local
export TOOLCHAIN_DIR=stock   # makes build-tags.sh emit not_tailscale_go
: "${ANDROID_HOME:?set ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -1d "$ANDROID_HOME"/ndk/* | sort -V | tail -n1)}"
TARGETS="${VM_GOMOBILE_TARGETS:-android/arm64,android/arm}"
GOBIN_DIR="$PWD/android/build/go/bin"
mkdir -p "$GOBIN_DIR" android/libs
GOBIN="$GOBIN_DIR" go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
export PATH="$GOBIN_DIR:$PATH"
[ -f tailscale.version ] || go run tailscale.com/cmd/mkversion > tailscale.version
UNSTRIPPED=android/libs/libtailscale_unstripped.aar
rm -f "$UNSTRIPPED" android/libs/libtailscale.aar
gomobile bind -target "$TARGETS" -androidapi 26 \
  -tags "$(./build-tags.sh) ts_omit_cachenetmap" \
  -ldflags "-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384 $(./version-ldflags.sh | tr '\n' ' ')" \
  -o "$UNSTRIPPED" ./libtailscale
mv "$UNSTRIPPED" android/libs/libtailscale.aar
ls -l android/libs/libtailscale.aar
