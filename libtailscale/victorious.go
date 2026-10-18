// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
// Modifications for Victorious Mesh.

package libtailscale

import "os"

func init() {
	// Victorious Mesh: never upload logs/diagnostics to Tailscale-operated
	// servers. TS_NO_LOGS_NO_SUPPORT disables logtail uploads and network
	// flow log uploads throughout tailscale.com.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
}
