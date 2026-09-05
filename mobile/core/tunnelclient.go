package core

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/xtaci/smux"
)

// The way out through the user's own server, rather than through a provider.
//
// The desktop runs `gost` as a child process for this and points its proxy at
// the local port that comes up. A phone cannot spawn a second process as
// comfortably, and it does not have to: gost is Go, this core is Go, and what
// gost is doing on the `mwss` path is four things that fit in one file.
//
// The shape, read out of gost v3.3.0 rather than guessed at - the desktop and
// the server were installed against that version and the framing has to match
// it exactly:
//
//	TCP     to a Cloudflare edge address on 443
//	TLS     with ServerName set to the domain. The name IS announced here,
//	        and that is not a lapse - Cloudflare has to see it to know which
//	        site the connection is for. It is the opposite decision from
//	        exit.go, for the opposite reason, and the two live side by side.
//	WS      an ordinary upgrade to wss://<domain><path>, binary messages
//	smux    version 1, keepalive 10s, everything else smux's own defaults
//	CONNECT one per stream, Basic relay:<password>
//
// The bulk path is the same without smux: one websocket per connection, so a
// single heavy transfer cannot stall everything sharing a session with it.
type TunnelMode string

const (
	// ModeSingle exits at the user's own server. One steady address.
	ModeSingle TunnelMode = "single"
	// ModeMulti exits at a provider node chosen through the server's API.
	ModeMulti TunnelMode = "multi"
	// ModeBulk is single without multiplexing.
	ModeBulk TunnelMode = "bulk"
)

// tunnelPaths is TUNNEL_PATHS from core/ovpn-proxy.py. Three copies of this
// agreement already exist - here, the desktop's config writer, and the
// server's installer - and they have to say the same thing or the upgrade is
// answered with a 404 that looks like a dead edge.
var tunnelPaths = map[TunnelMode]string{
	ModeSingle: "/gw",
	ModeMulti:  "/ex",
	ModeBulk:   "/gwb",
}

// muxed says whether a mode multiplexes. Only bulk does not.
func (m TunnelMode) muxed() bool { return m != ModeBulk }

func (m TunnelMode) path() string {
	if p, ok := tunnelPaths[m]; ok {
		return p
	}
	return tunnelPaths[ModeSingle]
}

// TunnelServer is one server, reachable through some set of edge addresses.
type TunnelServer struct {
	Domain   string   // the name the certificate serves and the Host header carries
	Password string   // the CONNECT credential, sent as relay:<this>
	Edges    []string // Cloudflare addresses to dial, quickest first

	// Session is the multiplexed session, held between CONNECTs. Nil until
	// the first one, and dropped whenever it breaks.
	mu   sync.Mutex
	sess *smux.Session
	mode TunnelMode
}

// NewTunnelServer prepares a client. Nothing is dialled until Open.
//
// With no edges, the domain is dialled by name - which is what the desktop
// does before it has measured any, and what works on a line that is not
// lying about this particular name.
func NewTunnelServer(domain, password string, edges []string, mode TunnelMode) *TunnelServer {
	return &TunnelServer{
		Domain: domain, Password: password, Edges: edges, mode: mode,
	}
}

// dialEdges opens the websocket, stepping over addresses that do not answer.
//
// fifo rather than spread across them, matching the desktop's selector: the
// list is in the order they were timed, so the first is the quickest and the
// rest are what to fall back to.
func (t *TunnelServer) dialEdges(ctx context.Context, timeout time.Duration) (net.Conn, error) {
	addrs := t.Edges
	if len(addrs) == 0 {
		addrs = []string{t.Domain}
	}

	var last error
	for _, addr := range addrs {
		conn, err := t.dialOne(ctx, addr, timeout)
		if err == nil {
			return conn, nil
		}
		last = fmt.Errorf("%s: %w", addr, err)
	}
	if last == nil {
		last = errors.New("no edge addresses to try")
	}
	return nil, last
}

func (t *TunnelServer) dialOne(ctx context.Context, addr string, timeout time.Duration) (net.Conn, error) {
	raw, err := (&net.Dialer{Timeout: timeout}).DialContext(
		ctx, "tcp", net.JoinHostPort(addr, "443"))
	if err != nil {
		return nil, err
	}
	if tcp, ok := raw.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	// gorilla dials the URL over the connection we hand it, and does the TLS
	// itself from TLSClientConfig - which is where the name goes. Announced,
	// unlike everywhere else in this package, because Cloudflare routes on it.
	dialer := websocket.Dialer{
		HandshakeTimeout: timeout,
		NetDial:          func(string, string) (net.Conn, error) { return raw, nil },
		TLSClientConfig:  &tls.Config{ServerName: t.Domain},
	}

	u := url.URL{Scheme: "wss", Host: t.Domain, Path: t.mode.path()}
	ws, resp, err := dialer.DialContext(ctx, u.String(), nil)
	if err != nil {
		raw.Close()
		if resp != nil {
			// A 404 here is not a dead address - it is the server not
			// serving that path, which means this build and that install
			// disagree about which mode lives where.
			return nil, fmt.Errorf("%w (the server answered %s for %s)",
				err, resp.Status, t.mode.path())
		}
		return nil, err
	}
	resp.Body.Close()
	return &wsConn{Conn: ws}, nil
}

// session returns the live multiplexed session, opening one if there is none.
func (t *TunnelServer) session(ctx context.Context, timeout time.Duration) (*smux.Session, error) {
	t.mu.Lock()
	defer t.mu.Unlock()

	if t.sess != nil && !t.sess.IsClosed() {
		return t.sess, nil
	}

	conn, err := t.dialEdges(ctx, timeout)
	if err != nil {
		return nil, err
	}

	// gost's own numbers: smux version 1, and the keepalive the desktop
	// writes into its config as mux.keepaliveInterval. Everything else is
	// smux's default, because gost leaves it there too.
	cfg := smux.DefaultConfig()
	cfg.Version = 1
	cfg.KeepAliveInterval = 10 * time.Second

	sess, err := smux.Client(conn, cfg)
	if err != nil {
		conn.Close()
		return nil, err
	}
	t.sess = sess
	return sess, nil
}

// Open asks the server for a tunnel to target ("host:port").
//
// Same signature as Exit.Open, deliberately: the tunnel above this does not
// care which of the two carried the bytes, and a caller that has to ask is a
// caller that will one day forget.
func (t *TunnelServer) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var stream net.Conn
	var err error

	if t.mode.muxed() {
		var sess *smux.Session
		sess, err = t.session(ctx, timeout)
		if err != nil {
			return nil, nil, err
		}
		stream, err = sess.OpenStream()
		if err != nil {
			// A session that will not open a stream is a session that has
			// gone: drop it so the next call dials rather than asking a
			// corpse a second time.
			t.mu.Lock()
			if t.sess == sess {
				t.sess = nil
			}
			t.mu.Unlock()
			return nil, nil, err
		}
	} else {
		stream, err = t.dialEdges(ctx, timeout)
		if err != nil {
			return nil, nil, err
		}
	}

	_ = stream.SetDeadline(time.Now().Add(timeout))
	spare, err := t.connect(stream, target)
	if err != nil {
		stream.Close()
		return nil, nil, err
	}
	_ = stream.SetDeadline(time.Time{})
	return stream, spare, nil
}

// connect is the CONNECT inside the stream. The same request exit.go sends,
// with a different credential and read the same way - by hand, because
// http.ReadResponse drains a 200 that has no body and takes the tunnel's
// first bytes with it. That was measured once already; see exit.go.
func (t *TunnelServer) connect(conn net.Conn, target string) ([]byte, error) {
	auth := base64.StdEncoding.EncodeToString([]byte("relay:" + t.Password))
	req := "CONNECT " + target + " HTTP/1.1\r\n" +
		"Host: " + target + "\r\n" +
		"Proxy-Authorization: Basic " + auth + "\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		return nil, err
	}

	br := bufio.NewReader(conn)
	line, err := br.ReadString('\n')
	if err != nil {
		return nil, fmt.Errorf("the server closed the stream on CONNECT: %w", err)
	}
	// Drain the rest of the head.
	for {
		h, err := br.ReadString('\n')
		if err != nil {
			return nil, err
		}
		if strings.TrimSpace(h) == "" {
			break
		}
	}

	code := ""
	if parts := strings.SplitN(strings.TrimSpace(line), " ", 3); len(parts) > 1 {
		code = parts[1]
	}
	switch {
	case code == "407":
		return nil, errors.New("the server refused the tunnel password")
	case !strings.HasPrefix(code, "2"):
		return nil, fmt.Errorf("CONNECT refused: %s", strings.TrimSpace(line))
	}

	// Anything bufio read ahead belongs to the tunnel and has to go back.
	if n := br.Buffered(); n > 0 {
		spare := make([]byte, n)
		if _, err := io.ReadFull(br, spare); err != nil {
			return nil, err
		}
		return spare, nil
	}
	return nil, nil
}

// Close ends the session. Safe to call twice.
func (t *TunnelServer) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.sess == nil {
		return nil
	}
	err := t.sess.Close()
	t.sess = nil
	return err
}

// wsConn is a websocket wearing net.Conn, the same adapter gost uses.
//
// The detail that matters is Read: a websocket delivers messages, not a byte
// stream, so a read that empties one message has to move to the next rather
// than report EOF. Getting that wrong gives a connection that carries exactly
// one frame and then looks closed.
type wsConn struct {
	*websocket.Conn
	r  io.Reader
	mu sync.Mutex
}

func (c *wsConn) Read(b []byte) (int, error) {
	for {
		if c.r == nil {
			_, r, err := c.Conn.NextReader()
			if err != nil {
				return 0, err
			}
			c.r = r
		}
		n, err := c.r.Read(b)
		if err == io.EOF {
			c.r = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, err
	}
}

func (c *wsConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.Conn.WriteMessage(websocket.BinaryMessage, b); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *wsConn) SetDeadline(t time.Time) error {
	if err := c.Conn.SetReadDeadline(t); err != nil {
		return err
	}
	return c.Conn.SetWriteDeadline(t)
}

func (c *wsConn) Close() error { return c.Conn.Close() }

var _ net.Conn = (*wsConn)(nil)
