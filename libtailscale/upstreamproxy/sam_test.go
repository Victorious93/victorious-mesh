// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeSAM is a minimal SAM v3.3 router: HELLO, SESSION CREATE,
// STREAM CONNECT (to registered I2P names), NAMING LOOKUP.
type fakeSAM struct {
	addr     string
	mu       sync.Mutex
	ver      string            // version to answer HELLO with; "" = NOVERSION
	names    map[string]string // i2p name -> real TCP address
	sessions int
	created  []string
	connects []string
	delay    time.Duration // delays SESSION STATUS (tunnel building)
	garbage  bool          // not a SAM bridge at all
	ctrl     []net.Conn
	ping     bool
}

func startFakeSAM(t testing.TB, names map[string]string) *fakeSAM {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeSAM{addr: ln.Addr().String(), ver: "3.3", names: names}
	t.Cleanup(func() {
		ln.Close()
		f.mu.Lock()
		for _, c := range f.ctrl {
			c.Close()
		}
		f.mu.Unlock()
	})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go f.serve(c)
		}
	}()
	return f
}

func (f *fakeSAM) serve(c net.Conn) {
	br := bufio.NewReader(c)
	closeIt := true
	defer func() {
		if closeIt {
			c.Close()
		}
	}()
	line, err := br.ReadString('\n')
	if err != nil {
		return
	}
	if f.garbage {
		io.WriteString(c, "HTTP/1.1 400 Bad Request\r\n\r\n")
		return
	}
	if !strings.HasPrefix(line, "HELLO VERSION") {
		return
	}
	if f.ver == "" {
		io.WriteString(c, "HELLO REPLY RESULT=NOVERSION\n")
		return
	}
	fmt.Fprintf(c, "HELLO REPLY RESULT=OK VERSION=%s\n", f.ver)
	cmd, err := br.ReadString('\n')
	if err != nil {
		return
	}
	switch {
	case strings.HasPrefix(cmd, "SESSION CREATE"):
		f.mu.Lock()
		f.sessions++
		f.created = append(f.created, strings.TrimSpace(cmd))
		d := f.delay
		f.ctrl = append(f.ctrl, c)
		ping := f.ping
		f.mu.Unlock()
		time.Sleep(d)
		io.WriteString(c, "SESSION STATUS RESULT=OK DESTINATION=FAKEDEST\n")
		closeIt = false
		go func() {
			if ping {
				io.WriteString(c, "PING hb\n")
			}
			for {
				l, err := br.ReadString('\n')
				if err != nil {
					c.Close()
					return
				}
				if strings.HasPrefix(l, "PONG") {
					f.mu.Lock()
					f.ping = false
					f.mu.Unlock()
				}
			}
		}()
	case strings.HasPrefix(cmd, "STREAM CONNECT"):
		kv := parseSAMLine(cmd).kv
		f.mu.Lock()
		f.connects = append(f.connects, strings.TrimSpace(cmd))
		real, ok := f.names[kv["DESTINATION"]]
		f.mu.Unlock()
		if !ok {
			io.WriteString(c, "STREAM STATUS RESULT=CANT_REACH_PEER\n")
			return
		}
		up, err := net.Dial("tcp", real)
		if err != nil {
			io.WriteString(c, "STREAM STATUS RESULT=CANT_REACH_PEER MESSAGE=\"down\"\n")
			return
		}
		defer up.Close()
		io.WriteString(c, "STREAM STATUS RESULT=OK\n")
		done := make(chan struct{}, 2)
		go func() { io.Copy(up, br); done <- struct{}{} }()
		go func() { io.Copy(c, up); done <- struct{}{} }()
		<-done
	case strings.HasPrefix(cmd, "NAMING LOOKUP"):
		kv := parseSAMLine(cmd).kv
		if _, ok := f.names[kv["NAME"]]; ok {
			fmt.Fprintf(c, "NAMING REPLY RESULT=OK NAME=%s VALUE=BASE64DEST\n", kv["NAME"])
		} else {
			fmt.Fprintf(c, "NAMING REPLY RESULT=KEY_NOT_FOUND NAME=%s\n", kv["NAME"])
		}
	}
}

func TestSAMStreamForwardsByName(t *testing.T) {
	target := echoServer(t)
	f := startFakeSAM(t, map[string]string{"forum.i2p": target})
	m := NewSAMManager(f.addr, nil, SAMOptions{})
	defer m.Close()
	c, err := m.DialStream(ctx3(t), "forum.i2p", 80)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if got := readLine(t, c); got != "hello-from-target" {
		t.Fatalf("banner %q", got)
	}
	st := m.Status()
	if st.Version != "3.3" || !st.SessionUp || st.OpenStreams != 1 {
		t.Fatalf("status %+v", st)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.connects) != 1 || !strings.Contains(f.connects[0], "DESTINATION=forum.i2p") || !strings.Contains(f.connects[0], "TO_PORT=80") {
		t.Fatalf("router saw %v", f.connects)
	}
	if !strings.Contains(f.created[0], "STYLE=STREAM") {
		t.Fatalf("session: %v", f.created)
	}
}

func TestSAMSessionSharedAndRecreatedAfterRouterRestart(t *testing.T) {
	target := echoServer(t)
	f := startFakeSAM(t, map[string]string{"a.i2p": target})
	m := NewSAMManager(f.addr, nil, SAMOptions{})
	defer m.Close()
	var wg sync.WaitGroup
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, err := m.DialStream(ctx3(t), "a.i2p", 80)
			if err != nil {
				t.Error(err)
				return
			}
			c.Close()
		}()
	}
	wg.Wait()
	f.mu.Lock()
	if f.sessions != 1 {
		t.Fatalf("created %d sessions for concurrent dials, want 1", f.sessions)
	}
	for _, c := range f.ctrl {
		c.Close() // router restart: control socket dies
	}
	f.mu.Unlock()
	deadline := time.Now().Add(2 * time.Second)
	for m.Status().SessionUp && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if m.Status().SessionUp {
		t.Fatal("manager did not notice the dead session")
	}
	c, err := m.DialStream(ctx3(t), "a.i2p", 80)
	if err != nil {
		t.Fatalf("no recovery: %v", err)
	}
	c.Close()
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sessions != 2 {
		t.Fatalf("sessions=%d", f.sessions)
	}
}

func TestSAMErrorsAreUseful(t *testing.T) {
	f := startFakeSAM(t, map[string]string{})
	m := NewSAMManager(f.addr, nil, SAMOptions{})
	defer m.Close()
	_, err := m.DialStream(ctx3(t), "nowhere.i2p", 80)
	var se *SAMError
	if !errors.As(err, &se) || se.Result != "CANT_REACH_PEER" || StageOf(err) != StageForward {
		t.Fatalf("got %v", err)
	}
	if !strings.Contains(err.Error(), "unreachable") {
		t.Fatalf("message not helpful: %v", err)
	}
	// Router with no SAM 3.x.
	old := startFakeSAM(t, nil)
	old.ver = ""
	_, err = NewSAMManager(old.addr, nil, SAMOptions{}).DialStream(ctx3(t), "x.i2p", 80)
	if !errors.As(err, &se) || se.Result != "NOVERSION" || StageOf(err) != StageNegotiate {
		t.Fatalf("got %v", err)
	}
	// Something that is not SAM (e.g. an HTTP proxy port).
	bad := startFakeSAM(t, nil)
	bad.garbage = true
	_, err = NewSAMManager(bad.addr, nil, SAMOptions{}).DialStream(ctx3(t), "x.i2p", 80)
	if StageOf(err) != StageNegotiate || !strings.Contains(err.Error(), "not a SAM bridge") {
		t.Fatalf("got %v", err)
	}
	// Nothing listening.
	_, err = NewSAMManager("127.0.0.1:1", nil, SAMOptions{}).DialStream(ctx3(t), "x.i2p", 80)
	if StageOf(err) != StageReach {
		t.Fatalf("got %v", err)
	}
}

func TestSAMStartupTimeoutAndCancel(t *testing.T) {
	f := startFakeSAM(t, nil)
	f.delay = 5 * time.Second // tunnels still building
	m := NewSAMManager(f.addr, nil, SAMOptions{StartupTimeout: 200 * time.Millisecond})
	defer m.Close()
	start := time.Now()
	_, err := m.DialStream(ctx3(t), "x.i2p", 80)
	if err == nil || time.Since(start) > 2*time.Second {
		t.Fatalf("err=%v after %v", err, time.Since(start))
	}
	if !strings.Contains(err.Error(), "did not come up within") {
		t.Fatalf("timeout message: %v", err)
	}
	// Caller cancel wins over a long startup timeout.
	m2 := NewSAMManager(f.addr, nil, SAMOptions{StartupTimeout: time.Minute})
	defer m2.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	start = time.Now()
	if _, err := m2.DialStream(ctx, "x.i2p", 80); err == nil || time.Since(start) > 2*time.Second {
		t.Fatalf("cancel ignored: %v after %v", err, time.Since(start))
	}
}

func TestSAMStreamBound(t *testing.T) {
	target := echoServer(t)
	f := startFakeSAM(t, map[string]string{"a.i2p": target})
	m := NewSAMManager(f.addr, nil, SAMOptions{MaxStreams: 2})
	defer m.Close()
	var open []net.Conn
	for i := 0; i < 2; i++ {
		c, err := m.DialStream(ctx3(t), "a.i2p", 80)
		if err != nil {
			t.Fatal(err)
		}
		open = append(open, c)
	}
	if _, err := m.DialStream(ctx3(t), "a.i2p", 80); !errors.Is(err, ErrSAMBusy) {
		t.Fatalf("bound not enforced: %v", err)
	}
	open[0].Close()
	c, err := m.DialStream(ctx3(t), "a.i2p", 80)
	if err != nil {
		t.Fatalf("slot not released: %v", err)
	}
	c.Close()
	open[1].Close()
	m.Close()
	if _, err := m.DialStream(ctx3(t), "a.i2p", 80); !errors.Is(err, ErrSAMClosed) {
		t.Fatalf("after Close: %v", err)
	}
}

func TestSAMNamingLookupAnswersPingAndLookup(t *testing.T) {
	f := startFakeSAM(t, map[string]string{"known.i2p": "127.0.0.1:1"})
	f.ping = true
	m := NewSAMManager(f.addr, nil, SAMOptions{})
	defer m.Close()
	if v, err := m.Lookup(ctx3(t), "known.i2p"); err != nil || v != "BASE64DEST" {
		t.Fatalf("%q %v", v, err)
	}
	_, err := m.Lookup(ctx3(t), "unknown.i2p")
	var se *SAMError
	if !errors.As(err, &se) || se.Result != "KEY_NOT_FOUND" {
		t.Fatalf("got %v", err)
	}
	// Router keepalive: PING gets a PONG on the control socket.
	if _, err := m.DialStream(ctx3(t), "known.i2p", 80); err == nil {
		// target port 1 refuses → expected failure, but session is created
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		f.mu.Lock()
		answered := !f.ping
		f.mu.Unlock()
		if answered {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("PING not answered")
		}
		time.Sleep(10 * time.Millisecond)
	}
}
