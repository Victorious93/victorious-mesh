// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

// phone is a second gVisor stack standing in for the Android apps: its
// packets are fed to the forced-proxy Stack under test exactly as the
// TUN would, and the Stack's replies are fed back. Everything between
// (flow accept, resolver choice, name lookup, dial, copy) is the real
// code path.
type phone struct {
	stk    *stack.Stack
	ep     *channel.Endpoint
	st     *Stack
	wg     sync.WaitGroup
	cancel context.CancelFunc
}

func newPhone(t testing.TB, resolve Resolver) *phone {
	t.Helper()
	p := &phone{}
	var err error
	p.st, err = NewStackWithResolver(1280, resolve, func(pkt []byte) {
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), pkt...))})
		p.ep.InjectInbound(ipv4.ProtocolNumber, pb)
	})
	if err != nil {
		t.Fatal(err)
	}
	p.ep = channel.New(256, 1280, "")
	p.stk = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
	})
	if e := p.stk.CreateNIC(1, p.ep); e != nil {
		t.Fatal(e)
	}
	local := tcpip.AddressWithPrefix{Address: tcpip.AddrFrom4([4]byte{10, 9, 9, 2}), PrefixLen: 24}
	if e := p.stk.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: local}, stack.AddressProperties{}); e != nil {
		t.Fatal(e)
	}
	p.stk.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	ctx, cancel := context.WithCancel(context.Background())
	p.cancel = cancel
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		for {
			pkt := p.ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			v := pkt.ToView()
			p.st.HandleInbound(v.AsSlice())
			v.Release()
			pkt.DecRef()
		}
	}()
	t.Cleanup(func() { cancel(); p.st.Close(); p.wg.Wait(); p.stk.Close() })
	return p
}

func (p *phone) dial(t testing.TB, dst netip.AddrPort) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	return gonet.DialContextTCP(ctx, p.stk, tcpip.FullAddress{
		NIC: 1, Addr: tcpip.AddrFrom4(dst.Addr().As4()), Port: dst.Port(),
	}, ipv4.ProtocolNumber)
}

func torDialer(t testing.TB, socks *socksProxy, names NameResolver) Dialer {
	h, port := hostPort(t, socks.addr)
	return Dialer{Cfg: Config{Kind: KindSOCKS5, Host: h, Port: port, FailClosed: true, Backend: BackendTor}, Quiet: true, Names: names}
}

// An app asks for an .onion name, gets a virtual address, connects to
// it, and the Tor SOCKS endpoint receives the NAME (never an IP, never
// a DNS lookup) through the real userspace stack.
func TestTransparentOnionFlowReachesTorByName(t *testing.T) {
	onion := makeOnion(9)
	target := echoServer(t)
	socks := startSOCKS(t, "", "", map[string]string{onion + ":80": target})
	m := NewVMap(16, time.Minute, nil)
	g := &NameGate{Map: m, Tor: func() bool { return true }}
	d := torDialer(t, socks, m)
	ph := newPhone(t, func(uint8, netip.AddrPort, netip.AddrPort) FlowDialer { return d })

	_, ans := parseReply(t, mustAnswer(t, g, onion, 1))
	vip := ans[0].Body.(*dnsmessage.AResource).A
	c, err := ph.dial(t, netip.AddrPortFrom(netip.AddrFrom4(vip), 80))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	io.WriteString(c, "ping\n")
	if got := readLine2(t, c); got != "ping" {
		t.Fatalf("echo %q", got)
	}
	ts := socks.targets()
	if len(ts) != 1 || ts[0] != "3:"+onion+":80" {
		t.Fatalf("Tor saw %v, want the onion name as a domain", ts)
	}
}

// An expired or invalidated mapping must fail the flow closed: the
// backend is never contacted and nothing goes direct.
func TestTransparentFlowFailsClosedWhenMappingGone(t *testing.T) {
	onion := makeOnion(10)
	target := echoServer(t)
	socks := startSOCKS(t, "", "", map[string]string{onion + ":80": target})
	clk := &fakeClock{t: time.Unix(5000, 0)}
	m := NewVMap(16, time.Minute, clk.now)
	g := &NameGate{Map: m, Tor: func() bool { return true }}
	direct := 0
	d := torDialer(t, socks, m)
	d.Direct = func(context.Context, string, string) (net.Conn, error) { direct++; return nil, io.EOF }
	ph := newPhone(t, func(uint8, netip.AddrPort, netip.AddrPort) FlowDialer { return d })

	_, ans := parseReply(t, mustAnswer(t, g, onion, 1))
	vip := netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)

	// Policy tightened (backend replaced): namespace invalidated.
	m.Invalidate("tor")
	if c, err := ph.dial(t, netip.AddrPortFrom(vip, 80)); err == nil {
		// gVisor completes the handshake before the upstream dial; the
		// flow must then die without data and without reaching Tor.
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		if n, _ := c.Read(make([]byte, 8)); n != 0 {
			t.Fatal("data flowed through an invalidated mapping")
		}
		c.Close()
	}
	// Expiry behaves the same.
	_, ans = parseReply(t, mustAnswer(t, g, onion, 1))
	vip = netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)
	clk.add(2 * time.Minute)
	if c, err := ph.dial(t, netip.AddrPortFrom(vip, 80)); err == nil {
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		if n, _ := c.Read(make([]byte, 8)); n != 0 {
			t.Fatal("data flowed through an expired mapping")
		}
		c.Close()
	}
	if len(socks.targets()) != 0 {
		t.Fatalf("Tor was contacted: %v", socks.targets())
	}
	if direct != 0 {
		t.Fatal("direct fallback used")
	}
}

// Tor down: protected flows are refused, not sent direct, even with
// FailClosed false (backends never fall back).
func TestTorBackendDownNeverFallsBackDirect(t *testing.T) {
	target := echoServer(t)
	direct := 0
	d := Dialer{
		Cfg:   Config{Kind: KindSOCKS5, Host: "127.0.0.1", Port: 1, FailClosed: false, Backend: BackendTor},
		Quiet: true,
		Direct: func(ctx context.Context, n, a string) (net.Conn, error) {
			direct++
			return net.Dial(n, a)
		},
	}
	if _, err := d.DialTCP(ctx3(t), target); err == nil || StageOf(err) != StageReach {
		t.Fatalf("got %v", err)
	}
	if _, err := d.DialTCP(ctx3(t), makeOnion(1)+":80"); err == nil {
		t.Fatal("onion dial succeeded with Tor down")
	}
	if direct != 0 {
		t.Fatal("fell back to direct")
	}
}

func TestReservedNamesRefusedWithoutMatchingBackend(t *testing.T) {
	socks := startSOCKS(t, "", "", nil)
	h, port := hostPort(t, socks.addr)
	plain := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: h, Port: port, FailClosed: true}, Quiet: true}
	for _, dest := range []string{makeOnion(2) + ":80", "forum.i2p:80", "printer.local:80", "expyuzz4wqqyqhjn.onion:80"} {
		if _, err := plain.DialTCP(ctx3(t), dest); err == nil {
			t.Fatalf("%s reached an ordinary proxy", dest)
		}
	}
	if len(socks.targets()) != 0 {
		t.Fatalf("proxy saw reserved names: %v", socks.targets())
	}
	// A Tor backend refuses I2P names and invalid onions; no leak either.
	tor := torDialer(t, socks, nil)
	for _, dest := range []string{"forum.i2p:80", "expyuzz4wqqyqhjn.onion:80", "198.18.0.77:80"} {
		if _, err := tor.DialTCP(ctx3(t), dest); err == nil {
			t.Fatalf("%s accepted by Tor backend", dest)
		}
	}
	if len(socks.targets()) != 0 {
		t.Fatalf("Tor saw: %v", socks.targets())
	}
}

func TestUDPBlockedForBackends(t *testing.T) {
	for _, b := range []Backend{BackendTor, BackendI2PSAM, BackendI2PHTTP} {
		d := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: "127.0.0.1", Port: 9, Backend: b}, Quiet: true}
		if _, err := d.DialUDP(ctx3(t), netip.MustParseAddrPort("8.8.8.8:443")); err == nil || !strings.Contains(err.Error(), "UDP") {
			t.Fatalf("%s: %v", b, err)
		}
	}
}

// I2P through SAM end to end, including the name coming from DNS.
func TestTransparentI2PFlowUsesSAMByName(t *testing.T) {
	target := echoServer(t)
	sam := startFakeSAM(t, map[string]string{"forum.i2p": target})
	mgr := NewSAMManager(sam.addr, nil, SAMOptions{})
	t.Cleanup(mgr.Close)
	m := NewVMap(16, time.Minute, nil)
	g := &NameGate{Map: m, I2P: func() bool { return true }}
	h, port := hostPort(t, sam.addr)
	d := Dialer{Cfg: Config{Kind: KindSAM, Host: h, Port: port, FailClosed: true, Backend: BackendI2PSAM}, Quiet: true, Names: m, SAM: mgr}
	ph := newPhone(t, func(uint8, netip.AddrPort, netip.AddrPort) FlowDialer { return d })
	_, ans := parseReply(t, mustAnswer(t, g, "forum.i2p", 1))
	vip := netip.AddrFrom4(ans[0].Body.(*dnsmessage.AResource).A)
	c, err := ph.dial(t, netip.AddrPortFrom(vip, 80))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	sam.mu.Lock()
	defer sam.mu.Unlock()
	if len(sam.connects) != 1 || !strings.Contains(sam.connects[0], "DESTINATION=forum.i2p") {
		t.Fatalf("router saw %v", sam.connects)
	}
}

// Clearnet through I2P needs an explicit outproxy; without it, refused.
func TestI2PClearnetOnlyViaExplicitOutproxy(t *testing.T) {
	target := echoServer(t)
	// The outproxy is an I2P service speaking HTTP CONNECT.
	outproxy := startConnectProxy(t, nil, "", "")
	sam := startFakeSAM(t, map[string]string{"exit.i2p": outproxy.addr})
	mgr := NewSAMManager(sam.addr, nil, SAMOptions{})
	t.Cleanup(mgr.Close)
	h, port := hostPort(t, sam.addr)
	d := Dialer{Cfg: Config{Kind: KindSAM, Host: h, Port: port, FailClosed: true, Backend: BackendI2PSAM}, Quiet: true, SAM: mgr}
	if _, err := d.DialTCP(ctx3(t), target); err != ErrNoOutproxy {
		t.Fatalf("clearnet without outproxy: %v", err)
	}
	if _, err := d.DialTCP(ctx3(t), "example.com:443"); err != ErrNoOutproxy {
		t.Fatalf("clearnet name without outproxy: %v", err)
	}
	d.Cfg.Outproxy = "exit.i2p"
	c, err := d.DialTCP(ctx3(t), target)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	if ts := outproxy.targets(); len(ts) != 1 || ts[0] != target {
		t.Fatalf("outproxy saw %v", ts)
	}
}

// A policy change rebuilds the TUN, which closes the userspace stack.
// Established flows must not survive it: their outside sockets close.
func TestStackCloseTearsDownEstablishedFlows(t *testing.T) {
	// A server that holds the upstream connection open and reports when it ends.
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	ended := make(chan struct{})
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		io.Copy(io.Discard, c) // returns when the proxy side closes
		close(ended)
	}()
	h, port := hostPort(t, ln.Addr().String())
	d := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: h, Port: port, FailClosed: true}, Quiet: true}
	socks := startSOCKS(t, "", "", map[string]string{"198.51.100.9:80": ln.Addr().String()})
	sh, sp := hostPort(t, socks.addr)
	d.Cfg.Host, d.Cfg.Port = sh, sp
	ph := newPhone(t, func(uint8, netip.AddrPort, netip.AddrPort) FlowDialer { return d })
	c, err := ph.dial(t, netip.MustParseAddrPort("198.51.100.9:80"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	io.WriteString(c, "hello\n") // flow is live end to end
	time.Sleep(200 * time.Millisecond)
	ph.st.Close() // policy change
	select {
	case <-ended:
	case <-time.After(3 * time.Second):
		t.Fatal("the upstream socket of an established flow outlived the stack")
	}
	ph.st.Close() // idempotent
}

// Backend restart recovery: the dialer holds no sticky "down" state, so
// once the Tor service is back the very next flow works, and while it
// is down every flow is refused (never direct).
func TestTorBackendRestartRecovery(t *testing.T) {
	web := echoServer(t)
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := ln.Addr().String()
	ln.Close() // Tor is not running yet
	h, port := hostPort(t, addr)
	d := Dialer{Cfg: Config{Kind: KindSOCKS5, Host: h, Port: port, FailClosed: true, Backend: BackendTor}, Quiet: true}
	for i := 0; i < 2; i++ {
		if _, err := d.DialTCP(ctx3(t), web); StageOf(err) != StageReach {
			t.Fatalf("while down: %v", err)
		}
	}
	// "Restart" Tor on the same port.
	ln2, err := net.Listen("tcp", addr)
	if err != nil {
		t.Skipf("port reuse: %v", err)
	}
	ln2.Close()
	socks := startSOCKSOn(t, addr, map[string]string{})
	_ = socks
	c, err := d.DialTCP(ctx3(t), web)
	if err != nil {
		t.Fatalf("no recovery after restart: %v", err)
	}
	c.Close()
}
