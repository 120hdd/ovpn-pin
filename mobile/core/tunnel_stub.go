//go:build !android && !linux && !darwin

package core

import "errors"

// Everything in tunnel.go needs a tun device, and a tun device on Windows is
// a driver rather than a file descriptor. The desktop half of this repo has
// no use for it either - it sets the system proxy instead, which is the whole
// reason winproxy.py exists - so the port is left unwritten rather than
// half-written, and says so here.
type Tunnel struct{}

var ErrNoTunnelHere = errors.New("carrying traffic through a tun is only built for Android, Linux and macOS")

func StartTunnel(int, int, Way) (*Tunnel, error) { return nil, ErrNoTunnelHere }

func (t *Tunnel) Exit() string      { return "" }
func (t *Tunnel) HostsJSON() string { return `{"live":false,"rows":[]}` }
func (t *Tunnel) Close() error      { return nil }
