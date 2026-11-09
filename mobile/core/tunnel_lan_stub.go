//go:build !android && !linux && !darwin

package core

// There is no tunnel on this platform (see tunnel_stub.go), so nothing to
// share. nil, and the caller says so.
func (t *Tunnel) LANBackend() LANBackend { return nil }
