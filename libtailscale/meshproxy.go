// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"strconv"
	"strings"
	"sync"

	"github.com/tailscale/tailscale-android/libtailscale/meshproxy"
	"tailscale.com/net/proxymux"
	"tailscale.com/net/socks5"
	"tailscale.com/types/logger"
)

var meshProxy struct {
	mu     sync.Mutex
	ln     net.Listener
	http   *http.Server
	bind   string
}

// StartMeshProxy listens on bindIP:port for SOCKS5 and HTTP CONNECT.
// bindIP must be this node's mesh address (never 0.0.0.0 / Wi-Fi / cellular).
// Optional username/password is required on both protocols when either is set.
func StartMeshProxy(bindIP string, port int32, username, password string) error {
	if err := meshproxy.ValidateBind(bindIP, port); err != nil {
		return err
	}
	if err := meshproxy.ValidateAuth(username, password); err != nil {
		return err
	}
	app := runningApp.Load()
	if app == nil {
		return errors.New("mesh app not started")
	}
	app.backendMu.Lock()
	lb := app.backend
	app.backendMu.Unlock()
	if lb == nil {
		return errors.New("mesh backend not ready")
	}
	dialer := lb.Dialer()
	if dialer == nil {
		return errors.New("mesh dialer not ready")
	}

	addr := net.JoinHostPort(bindIP, strconv.Itoa(int(port)))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}

	socksLn, httpLn := proxymux.SplitSOCKSAndHTTP(ln)
	hs := &http.Server{Handler: wrapProxyAuth(username, password, httpProxyHandler(dialer.UserDial))}
	ss := &socks5.Server{
		Logf:     logger.WithPrefix(log.Printf, "socks5: "),
		Dialer:   dialer.UserDial,
		Username: username,
		Password: password,
	}

	meshProxy.mu.Lock()
	StopMeshProxyLocked()
	meshProxy.ln = ln
	meshProxy.http = hs
	meshProxy.bind = addr
	meshProxy.mu.Unlock()

	go func() {
		if err := hs.Serve(httpLn); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
			log.Printf("mesh http proxy exited: %v", err)
		}
	}()
	go func() {
		if err := ss.Serve(socksLn); err != nil && !errors.Is(err, net.ErrClosed) {
			log.Printf("mesh socks5 proxy exited: %v", err)
		}
	}()
	log.Printf("mesh proxy listening on %s", addr)
	return nil
}

// StopMeshProxy closes the mesh-bound listeners.
func StopMeshProxy() {
	meshProxy.mu.Lock()
	defer meshProxy.mu.Unlock()
	StopMeshProxyLocked()
}

func StopMeshProxyLocked() {
	if meshProxy.http != nil {
		_ = meshProxy.http.Close()
		meshProxy.http = nil
	}
	if meshProxy.ln != nil {
		_ = meshProxy.ln.Close()
		meshProxy.ln = nil
	}
	meshProxy.bind = ""
}

// MeshProxyRunning reports whether the mesh proxy listener is up.
func MeshProxyRunning() bool {
	meshProxy.mu.Lock()
	defer meshProxy.mu.Unlock()
	return meshProxy.ln != nil
}

// MeshProxyAddr is the current bind address, or empty.
func MeshProxyAddr() string {
	meshProxy.mu.Lock()
	defer meshProxy.mu.Unlock()
	return meshProxy.bind
}

func wrapProxyAuth(user, pass string, next http.Handler) http.Handler {
	if user == "" && pass == "" {
		return next
	}
	wantU := []byte(user)
	wantP := []byte(pass)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotU, gotP, ok := proxyBasicAuth(r)
		if !ok || subtle.ConstantTimeCompare([]byte(gotU), wantU) != 1 || subtle.ConstantTimeCompare([]byte(gotP), wantP) != 1 {
			w.Header().Set("Proxy-Authenticate", `Basic realm="Victorious Mesh"`)
			http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func proxyBasicAuth(r *http.Request) (string, string, bool) {
	if u, p, ok := r.BasicAuth(); ok {
		return u, p, true
	}
	h := r.Header.Get("Proxy-Authorization")
	const prefix = "Basic "
	if !strings.HasPrefix(h, prefix) {
		return "", "", false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(h[len(prefix):]))
	if err != nil {
		return "", "", false
	}
	user, pass, ok := strings.Cut(string(raw), ":")
	if !ok {
		return "", "", false
	}
	return user, pass, true
}

// httpProxyHandler is the userspace HTTP CONNECT / forward proxy from
// tailscaled (--outbound-http-proxy-listen), without process-killing log.Fatalf.
func httpProxyHandler(dialer func(ctx context.Context, netw, addr string) (net.Conn, error)) http.Handler {
	rp := &httputil.ReverseProxy{
		Director: func(r *http.Request) {},
		Transport: &http.Transport{
			DialContext: dialer,
		},
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			backURL := r.RequestURI
			if strings.HasPrefix(backURL, "/") || backURL == "*" {
				http.Error(w, "bogus RequestURI; must be absolute URL or CONNECT", 400)
				return
			}
			rp.ServeHTTP(w, r)
			return
		}
		dst := r.RequestURI
		c, err := dialer(r.Context(), "tcp", dst)
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		defer c.Close()
		hj, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "hijack not supported", 500)
			return
		}
		cc, ccbuf, err := hj.Hijack()
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		defer cc.Close()
		io.WriteString(cc, "HTTP/1.1 200 OK\r\n\r\n")
		var clientSrc io.Reader = ccbuf
		if ccbuf.Reader.Buffered() == 0 {
			clientSrc = cc
		}
		errc := make(chan error, 1)
		go func() {
			_, err := io.Copy(cc, c)
			errc <- err
		}()
		go func() {
			_, err := io.Copy(c, clientSrc)
			errc <- err
		}()
		<-errc
	})
}
