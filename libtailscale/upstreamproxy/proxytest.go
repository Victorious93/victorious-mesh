// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ParseProxyURI reads scheme://[user[:pass]@]host:port with schemes
// http, https, socks5, socks5h (hostnames are always passed to the
// proxy, so socks5 and socks5h are treated alike) and sam / i2p-sam.
// Hosts may be names, IPv4 or bracketed IPv6; the port is required and
// must be 1-65535. Credentials are percent-decoded. The result is never
// logged; use [RedactProxyURI] for display.
func ParseProxyURI(raw string) (Config, error) {
	raw = strings.TrimSpace(raw)
	u, err := url.Parse(raw)
	if err != nil {
		return Config{}, fmt.Errorf("not a valid proxy URI: %w", err)
	}
	scheme := strings.ToLower(u.Scheme)
	var kind Kind
	switch scheme {
	case "http":
		kind = KindHTTP
	case "https":
		kind = KindHTTPS
	case "socks5", "socks5h":
		kind = KindSOCKS5
	case "sam", "i2p-sam":
		kind = KindSAM
	default:
		return Config{}, fmt.Errorf("unsupported proxy scheme %q (use http, https, socks5, socks5h)", u.Scheme)
	}
	if u.Hostname() == "" {
		return Config{}, errors.New("proxy URI has no host")
	}
	if u.Port() == "" {
		return Config{}, errors.New("proxy URI needs an explicit port")
	}
	p, err := strconv.Atoi(u.Port())
	if err != nil || p < MinPort || p > MaxPort {
		return Config{}, fmt.Errorf("port must be %d-%d", MinPort, MaxPort)
	}
	if p := strings.Trim(u.Path, "/"); p != "" || u.RawQuery != "" || u.Fragment != "" {
		return Config{}, errors.New("proxy URI must not have a path, query or fragment")
	}
	cfg := Config{Kind: kind, Host: u.Hostname(), Port: int32(p), FailClosed: true}
	if kind == KindSAM {
		cfg.Backend = BackendI2PSAM
	}
	if u.User != nil {
		cfg.Username = u.User.Username()
		cfg.Password, _ = u.User.Password()
	}
	if err := Validate(cfg); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// RedactProxyURI renders cfg as a URI with the password removed. The
// username is kept (it is not a secret by itself) but shown as set/unset
// only when hideUser is true.
func RedactProxyURI(cfg Config, hideUser bool) string {
	scheme := string(cfg.Kind)
	host := cfg.Host
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		host = "[" + host + "]"
	}
	auth := ""
	if cfg.Username != "" {
		if hideUser {
			auth = "***:***@"
		} else {
			auth = url.PathEscape(cfg.Username) + ":***@"
		}
	}
	return scheme + "://" + auth + host + ":" + strconv.Itoa(int(cfg.Port))
}

// StepResult is one line of a connection test.
type StepResult struct {
	Stage  Stage  `json:"stage"`
	OK     bool   `json:"ok"`
	Skip   bool   `json:"skipped,omitempty"`
	Detail string `json:"detail"`
	Millis int64  `json:"ms"`
}

// TestReport is the outcome of [RunTest]. Verdict is "forwarding" only
// when a request actually crossed the proxy and got a reply.
type TestReport struct {
	Steps   []StepResult `json:"steps"`
	Verdict string       `json:"verdict"` // "ok" or the failing stage
	// IsTor is set only from a reply of the Tor Project's check service.
	IsTor *bool `json:"isTor,omitempty"`
}

// TestTarget is what the forwarding stage requests.
type TestTarget struct {
	Host  string // name or IP; names are passed unresolved to the proxy
	Port  int
	TLS   bool   // verify TLS to the target
	Path  string // default "/"
	Tor   bool   // parse the /api/ip style reply for IsTor
	Roots *tls.Config
}

// RunTest walks the stages in order and stops at the first failure:
// reachability → TLS (https proxies) → authentication + negotiation +
// forwarding (through a real CONNECT/SOCKS5/SAM request to the target,
// then an actual HTTP request over the tunnel). A reachable port alone
// is never reported as success.
func RunTest(ctx context.Context, d Dialer, tgt TestTarget) TestReport {
	var rep TestReport
	add := func(s Stage, ok bool, detail string, t0 time.Time) {
		rep.Steps = append(rep.Steps, StepResult{Stage: s, OK: ok, Detail: detail, Millis: time.Since(t0).Milliseconds()})
	}
	skip := func(s Stage, why string) {
		rep.Steps = append(rep.Steps, StepResult{Stage: s, Skip: true, Detail: why})
	}
	fail := func(s Stage) TestReport { rep.Verdict = string(s); return rep }

	if tgt.Path == "" {
		tgt.Path = "/"
	}
	if tgt.Port == 0 {
		tgt.Port = 443
		if !tgt.TLS {
			tgt.Port = 80
		}
	}
	dest := net.JoinHostPort(tgt.Host, strconv.Itoa(tgt.Port))

	// 1. reachability
	t0 := time.Now()
	c, err := d.dialProxy(ctx)(ctx, "tcp", d.Cfg.ProxyAddr())
	if err != nil {
		add(StageReach, false, "cannot connect to "+d.Cfg.ProxyAddr()+": "+err.Error(), t0)
		return fail(StageReach)
	}
	c.Close()
	add(StageReach, true, "TCP connection to the proxy endpoint succeeded", t0)

	// 2. TLS (own handshake so the certificate details can be shown)
	if d.Cfg.Kind == KindHTTPS {
		t0 = time.Now()
		pc, err := d.connectProxy(ctx)
		if err != nil {
			detail := err.Error()
			var se *StageError
			if errors.As(err, &se) && se.Cert {
				detail = "certificate not trusted for " + d.Cfg.TLSName() + ": " + se.Err.Error() + " (never retried without TLS)"
			}
			add(StageTLS, false, detail, t0)
			return fail(StageTLS)
		}
		detail := "TLS verified for " + d.Cfg.TLSName()
		if tc, ok := pc.(*tls.Conn); ok {
			st := tc.ConnectionState()
			detail += fmt.Sprintf(" (%s)", tls.VersionName(st.Version))
		}
		pc.Close()
		add(StageTLS, true, detail, t0)
	} else {
		skip(StageTLS, "proxy endpoint is not TLS")
	}

	// 3. authentication + negotiation + forwarding via a real tunnel
	t0 = time.Now()
	tun, err := d.DialTCP(ctx, dest)
	if err != nil {
		st := StageOf(err)
		if st == "" {
			st = StageNegotiate
		}
		switch st {
		case StageAuth:
			add(StageAuth, false, err.Error(), t0)
		case StageForward:
			skip(StageAuth, "credentials were accepted or not required")
			add(StageNegotiate, true, "proxy protocol understood", t0)
			add(StageForward, false, "proxy could not reach "+dest+": "+err.Error(), t0)
		default:
			add(StageNegotiate, false, err.Error(), t0)
		}
		return fail(st)
	}
	defer tun.Close()
	if d.Cfg.Username != "" {
		add(StageAuth, true, "credentials accepted", t0)
	} else {
		skip(StageAuth, "no credentials configured")
	}
	add(StageNegotiate, true, "proxy protocol negotiated", t0)

	// 4. an actual request across the tunnel
	t0 = time.Now()
	var rw io.ReadWriter = tun
	if tgt.TLS {
		var base *tls.Config
		if tgt.Roots != nil {
			base = tgt.Roots.Clone()
		} else {
			base = &tls.Config{}
		}
		base.ServerName = tgt.Host
		base.InsecureSkipVerify = false
		base.MinVersion = tls.VersionTLS12
		tc := tls.Client(tun, base)
		if err := tc.HandshakeContext(ctx); err != nil {
			add(StageForward, false, "tunnel opened but TLS to the target failed: "+err.Error(), t0)
			return fail(StageForward)
		}
		rw = tc
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = tun.SetDeadline(dl)
	} else {
		_ = tun.SetDeadline(time.Now().Add(30 * time.Second))
	}
	req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: victorious-mesh-test\r\nAccept: */*\r\nConnection: close\r\n\r\n", tgt.Path, tgt.Host)
	if _, err := io.WriteString(rw, req); err != nil {
		add(StageForward, false, "write over tunnel: "+err.Error(), t0)
		return fail(StageForward)
	}
	resp, err := http.ReadResponse(bufio.NewReader(io.LimitReader(rw, 1<<20)), nil)
	if err != nil {
		add(StageForward, false, "no HTTP reply over the tunnel: "+err.Error(), t0)
		return fail(StageForward)
	}
	defer resp.Body.Close()
	add(StageForward, true, "HTTP "+resp.Status+" received through the proxy", t0)
	if tgt.Tor {
		var body struct {
			IsTor bool `json:"IsTor"`
		}
		if json.NewDecoder(io.LimitReader(resp.Body, 4096)).Decode(&body) == nil {
			rep.IsTor = &body.IsTor
		}
	}
	rep.Verdict = "ok"
	return rep
}
