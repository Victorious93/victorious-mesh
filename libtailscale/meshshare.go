// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/tailscale/tailscale-android/libtailscale/meshproxy"
	"github.com/tailscale/tailscale-android/libtailscale/meshshare"
)

var meshShare struct {
	mu   sync.Mutex
	ln   net.Listener
	srv  *http.Server
	bind string
}

// StartMeshShare serves dir read-only on bindIP:port. bindIP must be this
// node's mesh address (never 0.0.0.0, Wi-Fi, or cellular). token is the
// share password (HTTP Basic auth). Off unless the app calls this.
func StartMeshShare(bindIP string, port int32, dir, token string) error {
	if err := meshproxy.ValidateBind(bindIP, port); err != nil {
		return err
	}
	if err := meshshare.ValidToken(token); err != nil {
		return err
	}
	if st, err := os.Stat(dir); err != nil || !st.IsDir() {
		return errors.New("share folder missing")
	}
	addr := net.JoinHostPort(bindIP, strconv.Itoa(int(port)))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Handler:           meshshare.Handler(dir, token),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	meshShare.mu.Lock()
	stopMeshShareLocked()
	meshShare.ln, meshShare.srv, meshShare.bind = ln, srv, addr
	meshShare.mu.Unlock()
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			log.Printf("mesh share exited: %v", err)
		}
	}()
	log.Printf("mesh share listening on %s", addr)
	return nil
}

// StopMeshShare closes the share listener.
func StopMeshShare() {
	meshShare.mu.Lock()
	defer meshShare.mu.Unlock()
	stopMeshShareLocked()
}

func stopMeshShareLocked() {
	if meshShare.srv != nil {
		_ = meshShare.srv.Close()
		meshShare.srv = nil
	}
	if meshShare.ln != nil {
		_ = meshShare.ln.Close()
		meshShare.ln = nil
	}
	meshShare.bind = ""
}

// MeshShareRunning reports whether the share listener is up.
func MeshShareRunning() bool {
	meshShare.mu.Lock()
	defer meshShare.mu.Unlock()
	return meshShare.ln != nil
}

// MeshShareAddr is the current bind address, or empty.
func MeshShareAddr() string {
	meshShare.mu.Lock()
	defer meshShare.mu.Unlock()
	return meshShare.bind
}
