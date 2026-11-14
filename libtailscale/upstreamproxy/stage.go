// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"errors"
	"fmt"
)

// Stage says how far a proxy attempt got. It is what a connection test
// reports, so "the port is open" is never confused with "traffic flows".
type Stage string

const (
	StageReach     Stage = "reachability" // TCP connect to the proxy endpoint
	StageTLS       Stage = "tls"          // TLS handshake + certificate check (https proxies)
	StageAuth      Stage = "authentication"
	StageNegotiate Stage = "negotiation" // protocol framing (CONNECT / SOCKS5 / SAM)
	StageForward   Stage = "forwarding"  // the proxy accepted but could not reach the target
)

// StageError is a failure at one named stage.
type StageError struct {
	Stage Stage
	Err   error
	// Cert is true for certificate verification failures (unknown CA,
	// wrong name, expired). Those are never retried in plaintext.
	Cert bool
}

func (e *StageError) Error() string { return fmt.Sprintf("%s: %v", e.Stage, e.Err) }
func (e *StageError) Unwrap() error { return e.Err }

// StageOf returns the stage of err, or "" if it is not a stage error.
func StageOf(err error) Stage {
	var se *StageError
	if errors.As(err, &se) {
		return se.Stage
	}
	return ""
}
