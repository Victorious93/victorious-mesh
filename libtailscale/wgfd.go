// Copyright (c) Victorious Mesh authors
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import "syscall"

func closeFD(fd int32) { _ = syscall.Close(int(fd)) }
