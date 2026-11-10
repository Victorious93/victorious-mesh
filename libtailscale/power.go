// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"

// SetPowerSaver is called by the app's battery saver (off by default).
// It only changes how often background checks run.
func SetPowerSaver(on bool) {
	upstreamproxy.SetPowerSaver(on)
}
