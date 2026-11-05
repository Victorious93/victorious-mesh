// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"errors"
	"fmt"
	"syscall"
)

// ErrNotProtected means a socket to the proxy could not be excluded
// from the VPN. Dialing anyway would loop the socket back into the
// TUN (and, without the kill switch, could leave on the underlay).
var ErrNotProtected = errors.New("forced upstream: socket not protected from the VPN")

// ProtectControl returns a net.Dialer Control func that calls protect
// on the raw socket and fails the dial when protect is unavailable
// (nil, e.g. the VpnService is gone) or reports false.
func ProtectControl(protect func(fd int32) bool) func(network, address string, c syscall.RawConn) error {
	return func(_, _ string, c syscall.RawConn) error {
		if protect == nil {
			return ErrNotProtected
		}
		var ok bool
		if err := c.Control(func(fd uintptr) { ok = protect(int32(fd)) }); err != nil {
			return err
		}
		if !ok {
			return fmt.Errorf("%w", ErrNotProtected)
		}
		return nil
	}
}
