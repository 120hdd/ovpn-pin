package core

import (
	"fmt"
	"net"
	"strconv"
	"time"

	sscore "github.com/shadowsocks/go-shadowsocks2/core"
	"github.com/shadowsocks/go-shadowsocks2/socks"
)

// The user's own server, reached straight at its address over Shadowsocks.
//
// The other way in is the WebSocket: TLS to a Cloudflare edge (or to the
// address), an upgrade, a smux session, a CONNECT per stream. That survives
// a line that filters the server's address, because the address it dials is
// Cloudflare's. This one does not - it is TCP to the server itself - and in
// exchange it has nothing in the way: no CDN, no TLS of its own, no session
// to go stale. When the address is reachable it is the quicker of the two,
// and when the server is inside Iran it is the only one whose bytes count as
// domestic.
//
// The server end is gost's `ss` handler (see tunnel/install-server.sh): AEAD
// Shadowsocks, one key, two ports - one that exits at the server and one that
// goes on through the same exit-chain the /ex WebSocket path uses. Which of
// the two this dials is the caller's choice of port.
//
// Per connection, nothing multiplexed: the AEAD stream is the whole of the
// framing, and a connection that dies takes nothing else with it.
type ShadowsocksServer struct {
	Host string
	Port int

	cipher sscore.Cipher
	err    error // a method that could not be set up, said on the first Open
}

// NewShadowsocksServer prepares a client. Nothing is dialled until Open.
//
// method is one of the AEAD names gost and every other client agree on -
// chacha20-ietf-poly1305, aes-256-gcm, aes-128-gcm - and the key is derived
// from the password the standard way.
func NewShadowsocksServer(host string, port int, method, password string) *ShadowsocksServer {
	s := &ShadowsocksServer{Host: host, Port: port}
	if password == "" {
		s.err = fmt.Errorf("no Shadowsocks password for %s", host)
		return s
	}
	s.cipher, s.err = sscore.PickCipher(method, nil, password)
	if s.err != nil {
		s.err = fmt.Errorf("Shadowsocks method %q: %w", method, s.err)
	}
	return s
}

// Open asks the server for a tunnel to target ("host:port").
//
// The address goes out at once rather than with the first bytes the app
// sends - what gost calls nodelay - so a protocol where the far end speaks
// first is not left waiting for a client that is waiting for it.
//
// Shadowsocks has no answer to a request: a target the server cannot reach
// is a connection that closes. So unlike the other two ways, a refused
// target and a dead server look the same from here until bytes fail to come
// back.
func (s *ShadowsocksServer) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	if s.err != nil {
		return nil, nil, s.err
	}
	addr := socks.ParseAddr(target)
	if addr == nil {
		return nil, nil, fmt.Errorf("not a host and port: %q", target)
	}
	// dialControl keeps this socket out of the phone's own tunnel. See
	// protect.go; without it the server's address is routed into the tun
	// and the phone talks to itself.
	dialer := net.Dialer{Timeout: timeout, Control: dialControl}
	raw, err := dialer.Dial("tcp", net.JoinHostPort(s.Host, strconv.Itoa(s.Port)))
	if err != nil {
		return nil, nil, err
	}
	conn := s.cipher.StreamConn(raw)
	_ = conn.SetWriteDeadline(time.Now().Add(timeout))
	if _, err := conn.Write(addr); err != nil {
		raw.Close()
		return nil, nil, err
	}
	_ = conn.SetWriteDeadline(time.Time{})
	return conn, nil, nil
}

// Close has nothing to end: there is no session between connections.
func (s *ShadowsocksServer) Close() error { return nil }

// SeenPin is empty: nothing on this path has a certificate. The server's API
// still does, and is pinned separately (see serverapi.go).
func (s *ShadowsocksServer) SeenPin() string { return "" }

// ServerWay is the user's own server, however it is reached - what the
// binding holds while one is carrying, and closes when it stops.
type ServerWay interface {
	Way
	Close() error
	SeenPin() string
}

var (
	_ ServerWay = (*TunnelServer)(nil)
	_ ServerWay = (*ShadowsocksServer)(nil)
)
