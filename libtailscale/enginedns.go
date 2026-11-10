// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/net/tsdial"
	"tailscale.com/types/logger"
)

// installEngineDNSGuard makes the mesh engine's own DNS lookups (DNS over
// TCP and DNS over HTTPS from the 100.100.100.100 forwarder) obey the
// forced proxy. Every other system dial keeps the exact default path
// (a protected netns socket). With the forced proxy off nothing changes.
//
// tsdial only exposes this hook as SetSystemDialerForTest; if a future
// tailscale.com version drops it, this file stops compiling instead of
// silently leaking.
func installEngineDNSGuard(d *tsdial.Dialer, logf logger.Logf, netMon *netmon.Monitor) {
	if netMon == nil {
		// Without a netmon, tsdial's SystemDial refuses every dial, so
		// there is nothing to leak; keep that behaviour.
		return
	}
	direct := netns.NewDialer(logf, netMon)
	g := upstreamproxy.EngineDNSGuard{
		Cfg:       upstreamproxy.Get,
		ProxyDial: protectedDial,
		Direct:    direct.DialContext,
	}
	d.SetSystemDialerForTest(g.Dial)
}
