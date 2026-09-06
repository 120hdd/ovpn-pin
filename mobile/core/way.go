package core

import (
	"net"
	"time"
)

// Way is anything that can be asked for a tunnel to somewhere.
//
// Two things satisfy it, and everything above deliberately cannot tell them
// apart:
//
//	Exit          CONNECT to a provider's HTTPS proxy, at a pinned address,
//	              with the name proved and never announced.
//	TunnelServer  CONNECT through the user's own server, behind Cloudflare,
//	              with the name announced because Cloudflare routes on it.
//
// They differ in almost every detail - including the one this repo is named
// after, and in opposite directions - and in none that a TCP connection
// cares about. So the tun stack, the DNS client and the "seen as" check each
// take a Way and none of them has an opinion about which.
//
// In its own file rather than in tunnel.go because tunnel.go is built only
// for the phones, and this interface is what the desktop-side commands and
// tests talk to as well.
type Way interface {
	// Open asks for a tunnel to target ("host:port"), and hands back anything
	// that arrived behind the answer - which belongs to the caller, not to
	// the next read.
	Open(target string, timeout time.Duration) (net.Conn, []byte, error)
}

var (
	_ Way = (*Exit)(nil)
	_ Way = (*TunnelServer)(nil)
)
