//go:build android || linux || darwin

package core

import (
	"context"
	"net"
	"net/netip"
	"time"
)

// tunnelLAN is a running tunnel as the LAN proxy sees it.
type tunnelLAN struct{ t *Tunnel }

// LANBackend is this tunnel's way out, offered to a LANProxy. It follows the
// tunnel: when SetWay replaces the exit, connections the proxy opens from
// then on go through the new one, exactly as the phone's own do.
func (t *Tunnel) LANBackend() LANBackend { return tunnelLAN{t} }

func (b tunnelLAN) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	return b.t.open(target, timeout)
}

// Straight asks split tunnelling the same questions the phone's own
// connections are asked, as far as they can be asked of a device that is not
// this one: the address, and the name (given, or remembered from the DNS
// answer that produced the address). The owning app cannot be asked - the
// connection belongs to no app here - so in "only these apps" mode a shared
// device is not one of them, and goes straight.
func (b tunnelLAN) Straight(host string) bool {
	s := b.t.Split()
	if s == nil {
		return false
	}
	listed := false
	if ip, err := netip.ParseAddr(host); err == nil {
		listed = s.AddrListed(ip)
		if !listed && b.t.names != nil {
			listed = s.NameListed(b.t.names.Name(ip.Unmap()))
		}
	} else {
		listed = s.NameListed(host)
	}
	return s.Direct(listed)
}

func (b tunnelLAN) DialStraight(ctx context.Context, target string, timeout time.Duration) (net.Conn, error) {
	return dialStraight(ctx, target, timeout)
}

func (b tunnelLAN) Hosts() *Hosts { return b.t.hosts }
