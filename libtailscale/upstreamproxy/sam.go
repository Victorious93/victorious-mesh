// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// SAM v3 client for an I2P router (https://geti2p.net/en/docs/api/samv3).
// One long-lived control session (a STREAM session with a transient
// destination) is kept per manager; every outgoing connection is a new
// socket that says HELLO and STREAM CONNECT against that session. The
// manager bounds concurrent streams, applies a realistic startup
// timeout (the router may still be building tunnels), and drops
// everything on Close or when the router closes the control socket.

// SAMDefaults.
const (
	SAMDefaultStartupTimeout = 3 * time.Minute
	SAMDefaultMaxStreams     = 64
	samMinVersion            = "3.0"
	samMaxVersion            = "3.3"
	samLineMax               = 64 << 10
)

// SAMError carries a SAM RESULT code.
type SAMError struct {
	Op      string
	Result  string
	Message string
}

func (e *SAMError) Error() string {
	s := fmt.Sprintf("i2p sam %s: %s", e.Op, e.Result)
	if e.Message != "" {
		s += " (" + e.Message + ")"
	}
	switch e.Result {
	case "CANT_REACH_PEER":
		s += ": the destination is offline or unreachable from your router"
	case "INVALID_KEY", "KEY_NOT_FOUND":
		s += ": unknown I2P name; your router's address book has no entry for it"
	case "TIMEOUT":
		s += ": timed out inside I2P; the router may still be building tunnels"
	case "NOVERSION":
		s += ": the router does not speak SAM 3.x"
	}
	return s
}

// ErrSAMClosed is returned after Close.
var ErrSAMClosed = errors.New("i2p sam: manager closed")

// ErrSAMBusy is returned when the per-manager stream bound is reached.
var ErrSAMBusy = errors.New("i2p sam: too many open streams")

// SAMStatus is a snapshot for the UI. Fields are only set from what the
// router actually said.
type SAMStatus struct {
	Version      string // negotiated SAM version, "" until a HELLO succeeded
	SessionUp    bool
	OpenStreams  int
	LastError    string
	SessionSince time.Time
}

type SAMOptions struct {
	StartupTimeout time.Duration
	MaxStreams     int
	// Outproxy is an optional I2P destination name of a clearnet
	// outproxy that speaks HTTP CONNECT. Empty: clearnet is refused.
	Outproxy string
}

type SAMManager struct {
	addr string
	dial DialFunc
	opt  SAMOptions

	mu       sync.Mutex
	closed   bool
	ver      string
	sessID   string
	ctrl     net.Conn
	since    time.Time
	starting chan struct{} // non-nil while a session is being created
	startErr error
	lastErr  string
	streams  int
}

// NewSAMManager targets a router's SAM bridge. dial must reach it
// without entering the TUN (protected socket, or loopback).
func NewSAMManager(addr string, dial DialFunc, opt SAMOptions) *SAMManager {
	if opt.StartupTimeout <= 0 {
		opt.StartupTimeout = SAMDefaultStartupTimeout
	}
	if opt.MaxStreams <= 0 {
		opt.MaxStreams = SAMDefaultMaxStreams
	}
	if dial == nil {
		dial = defaultDial
	}
	return &SAMManager{addr: addr, dial: dial, opt: opt}
}

// Outproxy returns the configured I2P outproxy name ("" = none).
func (m *SAMManager) Outproxy() string { return m.opt.Outproxy }

func (m *SAMManager) Status() SAMStatus {
	m.mu.Lock()
	defer m.mu.Unlock()
	return SAMStatus{Version: m.ver, SessionUp: m.ctrl != nil, OpenStreams: m.streams, LastError: m.lastErr, SessionSince: m.since}
}

// Close tears down the control session; open streams are not forcibly
// closed here (their owners hold them) but no new ones start.
func (m *SAMManager) Close() {
	m.mu.Lock()
	m.closed = true
	c := m.ctrl
	m.ctrl = nil
	m.mu.Unlock()
	if c != nil {
		c.Close()
	}
}

// samLine reads one reply line and splits it into key=value pairs.
type samReply struct {
	words []string
	kv    map[string]string
}

func parseSAMLine(line string) samReply {
	r := samReply{kv: map[string]string{}}
	var tok []string
	var cur strings.Builder
	inQ := false
	for _, ch := range strings.TrimSpace(line) {
		switch {
		case ch == '"':
			inQ = !inQ
		case ch == ' ' && !inQ:
			if cur.Len() > 0 {
				tok = append(tok, cur.String())
				cur.Reset()
			}
		default:
			cur.WriteRune(ch)
		}
	}
	if cur.Len() > 0 {
		tok = append(tok, cur.String())
	}
	for _, t := range tok {
		if i := strings.IndexByte(t, '='); i > 0 {
			r.kv[t[:i]] = t[i+1:]
		} else {
			r.words = append(r.words, t)
		}
	}
	return r
}

func samReadLine(br *bufio.Reader) (samReply, error) {
	line, err := br.ReadString('\n')
	if err != nil {
		return samReply{}, err
	}
	if len(line) > samLineMax {
		return samReply{}, errors.New("i2p sam: reply too long")
	}
	return parseSAMLine(line), nil
}

// samHello performs the version handshake on a fresh connection.
func samHello(c net.Conn, br *bufio.Reader) (string, error) {
	if _, err := fmt.Fprintf(c, "HELLO VERSION MIN=%s MAX=%s\n", samMinVersion, samMaxVersion); err != nil {
		return "", err
	}
	r, err := samReadLine(br)
	if err != nil {
		return "", fmt.Errorf("i2p sam: no HELLO reply (is this a SAM port?): %w", err)
	}
	if len(r.words) < 2 || r.words[0] != "HELLO" || r.words[1] != "REPLY" {
		return "", &StageError{Stage: StageNegotiate, Err: errors.New("i2p sam: not a SAM bridge (unexpected greeting)")}
	}
	if res := r.kv["RESULT"]; res != "OK" {
		return "", &StageError{Stage: StageNegotiate, Err: &SAMError{Op: "HELLO", Result: res, Message: r.kv["MESSAGE"]}}
	}
	return r.kv["VERSION"], nil
}

func (m *SAMManager) connect(ctx context.Context) (net.Conn, *bufio.Reader, string, error) {
	c, err := m.dial(ctx, "tcp", m.addr)
	if err != nil {
		return nil, nil, "", &StageError{Stage: StageReach, Err: err}
	}
	stop := watchCtx(ctx, c)
	defer stop()
	if dl, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(dl)
	}
	br := bufio.NewReader(c)
	ver, err := samHello(c, br)
	if err != nil {
		c.Close()
		if ctx.Err() != nil {
			return nil, nil, "", ctx.Err()
		}
		return nil, nil, "", err
	}
	_ = c.SetDeadline(time.Time{})
	return c, br, ver, nil
}

// ensureSession returns the live session id, creating it if needed.
// Concurrent callers share one creation attempt.
func (m *SAMManager) ensureSession(ctx context.Context) (id string, ver string, err error) {
	for {
		m.mu.Lock()
		if m.closed {
			m.mu.Unlock()
			return "", "", ErrSAMClosed
		}
		if m.ctrl != nil {
			id, ver = m.sessID, m.ver
			m.mu.Unlock()
			return id, ver, nil
		}
		if m.starting != nil {
			ch := m.starting
			m.mu.Unlock()
			select {
			case <-ch:
				continue
			case <-ctx.Done():
				return "", "", ctx.Err()
			}
		}
		ch := make(chan struct{})
		m.starting = ch
		m.mu.Unlock()

		err := m.createSession(ctx)

		m.mu.Lock()
		m.starting = nil
		if err != nil {
			m.lastErr = err.Error()
		} else {
			m.lastErr = ""
		}
		m.mu.Unlock()
		close(ch)
		if err != nil {
			return "", "", err
		}
	}
}

func (m *SAMManager) createSession(ctx context.Context) error {
	sctx, cancel := context.WithTimeout(ctx, m.opt.StartupTimeout)
	defer cancel()
	c, br, ver, err := m.connect(sctx)
	if err != nil {
		return err
	}
	var idb [6]byte
	_, _ = rand.Read(idb[:])
	id := "vm-" + hex.EncodeToString(idb[:])
	stop := watchCtx(sctx, c)
	_, werr := fmt.Fprintf(c, "SESSION CREATE STYLE=STREAM ID=%s DESTINATION=TRANSIENT SIGNATURE_TYPE=7 i2cp.messageReliability=none\n", id)
	var r samReply
	var rerr error
	if werr == nil {
		r, rerr = samReadLine(br)
	}
	stop()
	switch {
	case werr != nil:
		c.Close()
		return &StageError{Stage: StageNegotiate, Err: werr}
	case rerr != nil:
		c.Close()
		if sctx.Err() != nil {
			return &StageError{Stage: StageNegotiate, Err: fmt.Errorf("i2p session did not come up within %s: %w", m.opt.StartupTimeout, sctx.Err())}
		}
		return &StageError{Stage: StageNegotiate, Err: rerr}
	}
	if r.kv["RESULT"] != "OK" {
		c.Close()
		return &StageError{Stage: StageNegotiate, Err: &SAMError{Op: "SESSION CREATE", Result: r.kv["RESULT"], Message: r.kv["MESSAGE"]}}
	}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		c.Close()
		return ErrSAMClosed
	}
	m.ctrl, m.sessID, m.ver, m.since = c, id, ver, time.Now()
	m.mu.Unlock()
	// The session lives exactly as long as this socket. Watch it so a
	// router restart is noticed and the next dial builds a new session.
	go func() {
		for { // answer router keepalives (SAM 3.2 PING/PONG) until it closes
			line, err := br.ReadString('\n')
			if err != nil {
				break
			}
			if rest, ok := strings.CutPrefix(strings.TrimSpace(line), "PING"); ok {
				_, _ = fmt.Fprintf(c, "PONG%s\n", rest)
			}
		}
		m.mu.Lock()
		if m.ctrl == c {
			m.ctrl = nil
			m.sessID = ""
		}
		m.mu.Unlock()
		c.Close()
	}()
	return nil
}

// DialStream opens a stream to an I2P destination name (host.i2p,
// base32.b32.i2p, or a full base64 destination). port is the I2CP port
// and is only sent when the router negotiated SAM >= 3.2.
func (m *SAMManager) DialStream(ctx context.Context, name string, port int) (net.Conn, error) {
	m.mu.Lock()
	if m.streams >= m.opt.MaxStreams {
		m.mu.Unlock()
		return nil, ErrSAMBusy
	}
	m.streams++
	m.mu.Unlock()
	release := func() {
		m.mu.Lock()
		m.streams--
		m.mu.Unlock()
	}
	id, ver, err := m.ensureSession(ctx)
	if err != nil {
		release()
		return nil, err
	}
	c, br, _, err := m.connect(ctx)
	if err != nil {
		release()
		return nil, err
	}
	stop := watchCtx(ctx, c)
	cmd := fmt.Sprintf("STREAM CONNECT ID=%s DESTINATION=%s SILENT=false", id, name)
	if samAtLeast(ver, 3, 2) && port > 0 {
		cmd += " TO_PORT=" + strconv.Itoa(port)
	}
	_, werr := fmt.Fprintf(c, "%s\n", cmd)
	var r samReply
	var rerr error
	if werr == nil {
		r, rerr = samReadLine(br)
	}
	stop()
	if werr != nil || rerr != nil {
		c.Close()
		release()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		e := werr
		if e == nil {
			e = rerr
		}
		return nil, &StageError{Stage: StageNegotiate, Err: e}
	}
	if r.kv["RESULT"] != "OK" {
		c.Close()
		release()
		stage := StageForward
		if r.kv["RESULT"] == "INVALID_ID" {
			// Session vanished underneath us: force re-create next time.
			m.mu.Lock()
			if m.sessID == id && m.ctrl != nil {
				m.ctrl.Close()
				m.ctrl = nil
			}
			m.mu.Unlock()
			stage = StageNegotiate
		}
		return nil, &StageError{Stage: stage, Err: &SAMError{Op: "STREAM CONNECT", Result: r.kv["RESULT"], Message: r.kv["MESSAGE"]}}
	}
	return &samStream{Conn: c, r: br, release: release}, nil
}

type samStream struct {
	net.Conn
	r       *bufio.Reader
	once    sync.Once
	release func()
}

func (s *samStream) Read(p []byte) (int, error) { return s.r.Read(p) }
func (s *samStream) Close() error {
	s.once.Do(s.release)
	return s.Conn.Close()
}

// Lookup asks the router's address book for a name (NAMING LOOKUP).
// It returns the destination key; it never touches public DNS.
func (m *SAMManager) Lookup(ctx context.Context, name string) (string, error) {
	c, br, _, err := m.connect(ctx)
	if err != nil {
		return "", err
	}
	defer c.Close()
	stop := watchCtx(ctx, c)
	defer stop()
	if _, err := fmt.Fprintf(c, "NAMING LOOKUP NAME=%s\n", name); err != nil {
		return "", &StageError{Stage: StageNegotiate, Err: err}
	}
	r, err := samReadLine(br)
	if err != nil {
		return "", &StageError{Stage: StageNegotiate, Err: err}
	}
	if r.kv["RESULT"] != "OK" {
		return "", &StageError{Stage: StageForward, Err: &SAMError{Op: "NAMING LOOKUP", Result: r.kv["RESULT"], Message: r.kv["MESSAGE"]}}
	}
	return r.kv["VALUE"], nil
}

func samAtLeast(ver string, maj, min int) bool {
	parts := strings.SplitN(ver, ".", 2)
	if len(parts) != 2 {
		return false
	}
	a, e1 := strconv.Atoi(parts[0])
	b, e2 := strconv.Atoi(parts[1])
	return e1 == nil && e2 == nil && (a > maj || a == maj && b >= min)
}
