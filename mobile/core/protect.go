//go:build android || linux

package core

import (
	"errors"
	"syscall"
)

// SetSocketProtector hands the core a way to keep its own connection out of
// the tunnel it is carrying.
//
// This is the one piece without which nothing works at all, and it fails in
// the most confusing way available: once VpnService routes 0.0.0.0/0 into the
// tun, the socket this app opens to the exit is routed there too. The exit's
// address goes into the tunnel, comes back out of the stack, is dialled
// again, and the phone sits at a hundred percent of one core having a
// conversation with itself. Nothing errors. Nothing connects.
//
// VpnService.protect(fd) is Android's answer: it marks one socket as
// belonging to the tunnel's owner rather than to its traffic. It has to be
// called after the socket exists and before it connects, which is exactly
// where net.Dialer.Control runs.
func SetSocketProtector(protect func(fd int) bool) {
	if protect == nil {
		dialControl = nil
		return
	}
	dialControl = func(_, _ string, c syscall.RawConn) error {
		var ok bool
		if err := c.Control(func(fd uintptr) { ok = protect(int(fd)) }); err != nil {
			return err
		}
		if !ok {
			return errors.New("the socket to the exit could not be kept out of the tunnel")
		}
		return nil
	}
}
