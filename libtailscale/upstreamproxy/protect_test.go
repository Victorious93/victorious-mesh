// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package upstreamproxy

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func listenLocal(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	return ln.Addr().String()
}

func dialWith(protect func(int32) bool, addr string) error {
	d := net.Dialer{Control: ProtectControl(protect), Timeout: 2 * time.Second}
	c, err := d.DialContext(context.Background(), "tcp", addr)
	if err == nil {
		c.Close()
	}
	return err
}

func TestProtectControlFailsWhenProtectFalse(t *testing.T) {
	addr := listenLocal(t)
	if err := dialWith(func(int32) bool { return false }, addr); !errors.Is(err, ErrNotProtected) {
		t.Fatalf("want ErrNotProtected, got %v", err)
	}
}

func TestProtectControlFailsWithoutService(t *testing.T) {
	addr := listenLocal(t)
	if err := dialWith(nil, addr); !errors.Is(err, ErrNotProtected) {
		t.Fatalf("want ErrNotProtected, got %v", err)
	}
}

func TestProtectControlSucceeds(t *testing.T) {
	addr := listenLocal(t)
	called := false
	if err := dialWith(func(fd int32) bool { called = fd > 0; return true }, addr); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("protect not called with a real fd")
	}
}
