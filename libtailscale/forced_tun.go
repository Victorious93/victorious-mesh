// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"
	"os"

	"github.com/tailscale/tailscale-android/libtailscale/upstreamproxy"
	"github.com/tailscale/wireguard-go/tun"
)

type forcedTUN struct {
	inner tun.Device
	stack *upstreamproxy.Stack
}

func wrapForcedTUN(dev tun.Device) tun.Device {
	cfg := upstreamproxy.Get()
	if !cfg.Enabled() {
		return dev
	}
	w := &forcedTUN{inner: dev}
	mtu, _ := dev.MTU()
	if mtu <= 0 {
		mtu = defaultMTU
	}
	st, err := upstreamproxy.NewStack(uint32(mtu), upstreamproxy.Dialer{Cfg: cfg, Dial: protectedDial, Direct: protectedDial}, w.writeBack)
	if err != nil {
		log.Printf("forced upstream: stack: %v", err)
		return dev
	}
	w.stack = st
	log.Printf("forced upstream: TUN-to-proxy path on (%s %s failClosed=%v)", cfg.Kind, cfg.Host, cfg.FailClosed)
	return w
}

func (t *forcedTUN) writeBack(p []byte) {
	if len(p) == 0 {
		return
	}
	_, _ = t.inner.Write([][]byte{p}, 0)
}

func (t *forcedTUN) File() *os.File                 { return t.inner.File() }
func (t *forcedTUN) MTU() (int, error)              { return t.inner.MTU() }
func (t *forcedTUN) Name() (string, error)          { return t.inner.Name() }
func (t *forcedTUN) Events() <-chan tun.Event       { return t.inner.Events() }
func (t *forcedTUN) BatchSize() int                 { return t.inner.BatchSize() }
func (t *forcedTUN) Write(data [][]byte, offset int) (int, error) {
	return t.inner.Write(data, offset)
}

func (t *forcedTUN) Close() error {
	if t.stack != nil {
		t.stack.Close()
	}
	return t.inner.Close()
}

func (t *forcedTUN) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	for {
		n, err := t.inner.Read(slab, packets)
		if err != nil || n == 0 || t.stack == nil {
			return n, err
		}
		kept := 0
		for i := 0; i < n; i++ {
			raw := readPacketBytes(slab, packets[i])
			if upstreamproxy.IsMeshDest(raw) {
				if kept != i {
					packets[kept] = packets[i]
				}
				kept++
				continue
			}
			t.stack.HandleInbound(raw)
		}
		if kept > 0 {
			return kept, nil
		}
	}
}

func readPacketBytes(slab []byte, p tun.ReadPacket) []byte {
	end := p.Offset + p.Size
	if p.Offset < 0 || p.Size < 0 || end > len(slab) {
		return nil
	}
	return slab[p.Offset:end]
}
