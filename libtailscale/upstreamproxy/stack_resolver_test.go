// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"net/netip"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"
)

func tcpSYN(src, dst netip.AddrPort) []byte {
	const ipLen = header.IPv4MinimumSize
	const tcpLen = header.TCPMinimumSize
	pkt := make([]byte, ipLen+tcpLen)
	ip := header.IPv4(pkt)
	s := tcpip.AddrFrom4(src.Addr().As4())
	d := tcpip.AddrFrom4(dst.Addr().As4())
	ip.Encode(&header.IPv4Fields{
		TotalLength: uint16(len(pkt)),
		TTL:         64,
		Protocol:    uint8(header.TCPProtocolNumber),
		SrcAddr:     s,
		DstAddr:     d,
	})
	ip.SetChecksum(^ip.CalculateChecksum())
	tcp := header.TCP(pkt[ipLen:])
	tcp.Encode(&header.TCPFields{
		SrcPort:    src.Port(),
		DstPort:    dst.Port(),
		SeqNum:     1000,
		DataOffset: tcpLen,
		Flags:      header.TCPFlagSyn,
		WindowSize: 65535,
	})
	xsum := header.PseudoHeaderChecksum(header.TCPProtocolNumber, s, d, uint16(tcpLen))
	xsum = checksum.Checksum(tcp, xsum)
	tcp.SetChecksum(^xsum)
	return pkt
}

func TestStackAsksResolverWithAppSourceAndRefuses(t *testing.T) {
	var mu sync.Mutex
	type call struct {
		proto    uint8
		src, dst netip.AddrPort
	}
	var calls []call
	got := make(chan struct{}, 1)
	var out [][]byte
	st, err := NewStackWithResolver(1280, func(proto uint8, src, dst netip.AddrPort) FlowDialer {
		mu.Lock()
		calls = append(calls, call{proto, src, dst})
		mu.Unlock()
		select {
		case got <- struct{}{}:
		default:
		}
		return nil // refuse
	}, func(p []byte) {
		mu.Lock()
		out = append(out, append([]byte(nil), p...))
		mu.Unlock()
	})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	src := netip.MustParseAddrPort("100.64.0.5:41000")
	dst := netip.MustParseAddrPort("93.184.216.34:443")
	st.HandleInbound(tcpSYN(src, dst))
	select {
	case <-got:
	case <-time.After(3 * time.Second):
		t.Fatal("resolver not called")
	}
	mu.Lock()
	c := calls[0]
	mu.Unlock()
	if c.proto != 6 || c.src != src || c.dst != dst {
		t.Fatalf("resolver got %+v", c)
	}
	// Refused flows are reset, never relayed: wait for the RST.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(out)
		var rst bool
		for _, p := range out {
			if len(p) >= 40 && header.TCP(p[20:]).Flags()&header.TCPFlagRst != 0 {
				rst = true
			}
		}
		mu.Unlock()
		if rst {
			return
		}
		_ = n
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("no RST for refused flow")
}
