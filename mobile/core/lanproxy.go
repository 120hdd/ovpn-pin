package core

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Sharing the phone's way out with the devices around it.
//
// An iPhone on the same Wi-Fi - or one joined to this phone's hotspot - has
// a setting for an HTTP proxy, and nothing else it needs. Point it at the
// phone's address and a port, and its traffic leaves by whatever Relay is
// connected through, with no app installed on it and, above all, no
// credential on it: the exit's sign-in, the user's own server's key and the
// pinned addresses all stay in this process. The device being helped only
// ever learns an IP and a port.
//
// This is core/ovpn-proxy.py's serve_one, for the same reason it exists
// there: one byte decides which protocol is being spoken. SOCKS5 opens with
// its version, 0x05; every HTTP verb opens with a letter. So both fit on one
// port, and a client can be pointed at either without anything being
// reconfigured on this end.
//
// Two things here are easy to get backwards.
//
// Outbound goes through the Way, never straight. The listener's own sockets
// are in this app's process, which Android excludes from the VPN - so a
// plain net.Dial from here would leave by the bare line, to the very place
// the sharing was turned on to avoid, and it would work, which is the
// dangerous part: nothing would look wrong. The only exception is the one
// split tunnelling makes for the phone itself, taken by the same rules and
// dialled the same way (protected, see dialControl) so a shared device
// behaves like the phone it borrows.
//
// And the listener is open to the whole network it is on, which on a cafe's
// Wi-Fi is strangers. So a connection from anywhere but a private, link-local
// or carrier-grade-NAT address is closed before a byte is read from it, and
// an optional password turns the rest into an authenticated proxy.

// LANBackend is what the proxy needs from the running tunnel, and nothing
// more - so the protocol handling can be tested against a fake, and builds on
// the platforms that have no tunnel at all.
type LANBackend interface {
	// Open is one connection through the way out that is carrying now, with
	// whatever arrived behind the answer. Counted towards that way's health.
	Open(target string, timeout time.Duration) (net.Conn, []byte, error)
	// Straight is whether split tunnelling sends this destination around the
	// way out, on the phone's own line. host is a name or an address.
	Straight(host string) bool
	// DialStraight is one connection on the phone's own line.
	DialStraight(ctx context.Context, target string, timeout time.Duration) (net.Conn, error)
	// Hosts is where connections are counted; nil is allowed.
	Hosts() *Hosts
}

const (
	// LANProxyUser is the user name an HTTP client must give when a password
	// is set. SOCKS5 is not held to it: its RFC 1929 field is whatever the
	// client's settings box calls it, and only the password is the secret.
	LANProxyUser = "relay"

	lanMaxConns    = 256
	lanHandshake   = 30 * time.Second
	lanIdle        = 5 * time.Minute
	lanDialTimeout = 15 * time.Second
)

// LANProxy is one listener sharing a backend's way out.
type LANProxy struct {
	be       LANBackend
	password string

	// Overridable in tests: who may connect, and how long things may idle.
	allow func(net.IP) bool
	idle  time.Duration

	mu     sync.Mutex
	ln     net.Listener
	conns  map[net.Conn]struct{}
	closed bool

	live atomic.Int32
	wg   sync.WaitGroup
}

// NewLANProxy builds one that is not listening yet. An empty password means
// anyone on the network may use it.
func NewLANProxy(be LANBackend, password string) *LANProxy {
	return &LANProxy{
		be: be, password: password,
		allow: PrivateSource, idle: lanIdle,
		conns: map[net.Conn]struct{}{},
	}
}

// Start listens on every address of this device, on TCP port.
//
// "tcp4": the tun is IPv4 only, the addresses shown to the user are IPv4,
// and a listener that also answered on IPv6 would be reachable by a family
// the source filter was never written for.
func (p *LANProxy) Start(port int) error {
	ln, err := net.Listen("tcp4", net.JoinHostPort("0.0.0.0", strconv.Itoa(port)))
	if err != nil {
		return err
	}
	return p.Serve(ln)
}

// Serve runs on a listener the caller made. Returns at once; the accepting
// is on a goroutine of its own.
func (p *LANProxy) Serve(ln net.Listener) error {
	p.mu.Lock()
	if p.closed || p.ln != nil {
		p.mu.Unlock()
		ln.Close()
		return errors.New("this proxy was already started")
	}
	p.ln = ln
	p.mu.Unlock()
	p.wg.Add(1)
	go p.accept(ln)
	return nil
}

// Port is the port actually listening, 0 when not.
func (p *LANProxy) Port() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.ln == nil {
		return 0
	}
	if a, ok := p.ln.Addr().(*net.TCPAddr); ok {
		return a.Port
	}
	return 0
}

// Clients is how many connections are being carried right now.
func (p *LANProxy) Clients() int { return int(p.live.Load()) }

// Close stops listening and closes every connection in flight. Safe to call
// twice. Waits for the goroutines to finish, which they do promptly: every
// one of them is blocked on a socket that was just closed.
func (p *LANProxy) Close() error {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil
	}
	p.closed = true
	ln := p.ln
	open := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		open = append(open, c)
	}
	p.mu.Unlock()
	if ln != nil {
		ln.Close()
	}
	for _, c := range open {
		c.Close()
	}
	p.wg.Wait()
	return nil
}

// track remembers a connection so Close can reach it; false if closing has
// begun, in which case the caller must drop it.
func (p *LANProxy) track(c net.Conn) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return false
	}
	p.conns[c] = struct{}{}
	return true
}

func (p *LANProxy) untrack(c net.Conn) {
	p.mu.Lock()
	delete(p.conns, c)
	p.mu.Unlock()
}

// trackUp is track for the connection on the far side. Without it, Close
// would reach the client's socket but not the one dialled for it, and a
// goroutine blocked in a dial or a copy on the far side would hold Close up
// for as long as that took.
func (p *LANProxy) trackUp(c net.Conn) bool { return p.track(c) }

func (p *LANProxy) accept(ln net.Listener) {
	defer p.wg.Done()
	for {
		c, err := ln.Accept()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			return // closed
		}
		// Refused before anything is read. A stranger learns nothing from a
		// reset, and costs a goroutine for as long as it takes to close.
		ip := remoteIP(c)
		if ip == nil || !p.allow(ip) || int(p.live.Load()) >= lanMaxConns {
			c.Close()
			continue
		}
		p.live.Add(1)
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			defer p.live.Add(-1)
			p.serve(c, ip.String())
		}()
	}
}

func remoteIP(c net.Conn) net.IP {
	a, ok := c.RemoteAddr().(*net.TCPAddr)
	if !ok {
		return nil
	}
	return a.IP
}

// PrivateSource is whether an address is one a device on the same local
// network would have: 10/8, 172.16/12, 192.168/16, carrier-grade NAT
// 100.64/10 (some hotspots and tethers hand these out), and link-local
// 169.254/16. IPv4 only, like everything else here.
func PrivateSource(ip net.IP) bool {
	v4 := ip.To4()
	if v4 == nil {
		return false
	}
	switch {
	case v4[0] == 10:
		return true
	case v4[0] == 172 && v4[1]&0xf0 == 16:
		return true
	case v4[0] == 192 && v4[1] == 168:
		return true
	case v4[0] == 100 && v4[1]&0xc0 == 64:
		return true
	case v4[0] == 169 && v4[1] == 254:
		return true
	}
	return false
}

// LocalAddrs lists this device's private IPv4 addresses, for the screen to
// show next to the port. Loopback, interfaces that are down and tunnels are
// skipped, and so are the cellular interfaces: their address is the
// carrier's, and a neighbour on the Wi-Fi cannot reach it however it is
// typed in.
func LocalAddrs() []string {
	out := []string{}
	ifs, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		skip := false
		for _, pre := range []string{"tun", "tap", "ppp", "rmnet", "ccmni", "dummy", "lo"} {
			if strings.HasPrefix(ifc.Name, pre) {
				skip = true
				break
			}
		}
		if skip {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			n, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			if v4 := n.IP.To4(); v4 != nil && PrivateSource(v4) {
				out = append(out, v4.String())
			}
		}
	}
	return out
}

// idleConn pushes the deadline forward on every read and write, so what
// ends a connection is silence rather than age: a download that runs for an
// hour is fine, a client that vanished is not kept for an hour.
type idleConn struct {
	net.Conn
	d atomic.Int64
}

func (c *idleConn) Read(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(time.Duration(c.d.Load())))
	return c.Conn.Read(b)
}

func (c *idleConn) Write(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(time.Duration(c.d.Load())))
	return c.Conn.Write(b)
}

// serve is one client, start to finish.
func (p *LANProxy) serve(raw net.Conn, clientIP string) {
	conn := &idleConn{Conn: raw}
	conn.d.Store(int64(lanHandshake))
	if !p.track(conn) {
		raw.Close()
		return
	}
	defer p.untrack(conn)
	defer conn.Close()

	br := bufio.NewReaderSize(conn, 4096)
	first, err := br.Peek(1)
	if err != nil {
		return
	}
	if first[0] == 5 {
		p.serveSOCKS(conn, br, clientIP)
	} else {
		p.serveHTTP(conn, br, clientIP)
	}
}

// dial opens the far side of one client connection, the way the phone's own
// apps' connections are opened: through the way out, or around it when
// split tunnelling says so. Counted before the dial, for the reason carry
// gives: a destination that refuses should still appear in the list.
func (p *LANProxy) dial(target, clientIP string) (net.Conn, []byte, *hostRow, error) {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return nil, nil, nil, err
	}
	hosts := p.be.Hosts()
	row := hosts.opened(target, "", "LAN "+clientIP)
	var (
		up    net.Conn
		spare []byte
	)
	if p.be.Straight(host) {
		up, err = p.be.DialStraight(context.Background(), target, lanDialTimeout)
		if err == nil {
			hosts.straight(row)
		}
	} else {
		up, spare, err = p.be.Open(target, lanDialTimeout)
	}
	if err != nil {
		hosts.closed(row)
		return nil, nil, nil, err
	}
	if !p.trackUp(up) {
		up.Close()
		hosts.closed(row)
		return nil, nil, nil, errors.New("the proxy is closing")
	}
	return up, spare, row, nil
}

// pipe carries bytes both ways until either side ends, counting as they
// pass. client is the reader's view of the client - it may hold bytes
// already buffered - and conn the raw connection to write back on.
func (p *LANProxy) pipe(conn *idleConn, client io.Reader, up net.Conn, row *hostRow) {
	conn.d.Store(int64(p.idle))
	hosts := p.be.Hosts()
	toUp := counted{w: up, h: hosts, row: row}
	toClient := counted{w: conn, h: hosts, row: row, down: true}
	done := make(chan struct{}, 2)
	go func() { io.Copy(toUp, client); done <- struct{}{} }()
	go func() { io.Copy(toClient, up); done <- struct{}{} }()
	<-done
	// Closing both is what ends the other copy, so the wait is bounded.
	up.Close()
	conn.Close()
	<-done
}

// finish releases the far side once a client is done with it.
func (p *LANProxy) finish(up net.Conn, row *hostRow) {
	up.Close()
	p.untrack(up)
	p.be.Hosts().closed(row)
}

//------------------------------------------------------------------- HTTP

func (p *LANProxy) authorised(h http.Header) bool {
	if p.password == "" {
		return true
	}
	v := h.Get("Proxy-Authorization")
	const prefix = "Basic "
	if len(v) < len(prefix) || !strings.EqualFold(v[:len(prefix)], prefix) {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(v[len(prefix):]))
	if err != nil {
		return false
	}
	user, pass, ok := strings.Cut(string(raw), ":")
	if !ok {
		return false
	}
	// Both compared, both in constant time, and the answer taken from both -
	// so which of the two was wrong is not something the timing tells.
	u := subtle.ConstantTimeCompare([]byte(user), []byte(LANProxyUser))
	w := subtle.ConstantTimeCompare([]byte(pass), []byte(p.password))
	return u&w == 1
}

func httpReply(w io.Writer, status int, extra string) {
	fmt.Fprintf(w, "HTTP/1.1 %d %s\r\n%sContent-Length: 0\r\nConnection: close\r\n\r\n",
		status, http.StatusText(status), extra)
}

// withPort adds a default port to a host that has none.
func withPort(hostport, def string) string {
	if _, _, err := net.SplitHostPort(hostport); err == nil {
		return hostport
	}
	return net.JoinHostPort(strings.Trim(hostport, "[]"), def)
}

func (p *LANProxy) serveHTTP(conn *idleConn, br *bufio.Reader, clientIP string) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if !p.authorised(req.Header) {
		httpReply(conn, http.StatusProxyAuthRequired, "Proxy-Authenticate: Basic realm=\"Relay\"\r\n")
		return
	}

	if req.Method == http.MethodConnect {
		target := withPort(req.Host, "443")
		up, spare, row, err := p.dial(target, clientIP)
		if err != nil {
			httpReply(conn, http.StatusBadGateway, "")
			return
		}
		defer p.finish(up, row)
		if _, err := io.WriteString(conn, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
		if len(spare) > 0 {
			if _, err := conn.Write(spare); err != nil {
				return
			}
			p.be.Hosts().moved(row, 0, int64(len(spare)))
		}
		// Whatever the client sent behind its CONNECT is already in br.
		p.pipe(conn, br, up, row)
		return
	}

	// A plain request carries its destination in an absolute URI; one that
	// does not was addressed to this proxy as if it were a web server.
	if req.URL == nil || req.URL.Host == "" {
		httpReply(conn, http.StatusBadRequest, "")
		return
	}
	def := "80"
	if req.URL.Scheme == "https" {
		def = "443"
	}
	target := withPort(req.URL.Host, def)
	up, spare, row, err := p.dial(target, clientIP)
	if err != nil {
		httpReply(conn, http.StatusBadGateway, "")
		return
	}
	hosts := p.be.Hosts()
	defer p.finish(up, row)

	// Origin-form, as a server expects, with what was addressed to the proxy
	// taken off. One request per connection: with Connection: close the
	// answer ends when the server closes, and there is no pipelining to get
	// wrong between the client and a server it never knew it was talking to.
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authenticate")
	req.Header.Del("Connection")
	req.RequestURI = ""
	req.Close = true
	conn.d.Store(int64(p.idle))
	if err := req.Write(counted{w: up, h: hosts, row: row}); err != nil {
		httpReply(conn, http.StatusBadGateway, "")
		return
	}
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			return
		}
		hosts.moved(row, 0, int64(len(spare)))
	}
	p.pipe(conn, br, up, row)
}

//------------------------------------------------------------------ SOCKS5

func (p *LANProxy) serveSOCKS(conn *idleConn, br *bufio.Reader, clientIP string) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(br, head); err != nil {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	want := byte(0x00)
	if p.password != "" {
		want = 0x02
	}
	found := false
	for _, m := range methods {
		if m == want {
			found = true
		}
	}
	if !found {
		conn.Write([]byte{5, 0xff})
		return
	}
	if _, err := conn.Write([]byte{5, want}); err != nil {
		return
	}

	if want == 0x02 {
		// RFC 1929: ver 1, ulen, user, plen, password.
		ver, err := br.ReadByte()
		if err != nil || ver != 1 {
			return
		}
		ul, err := br.ReadByte()
		if err != nil {
			return
		}
		if _, err := io.ReadFull(br, make([]byte, ul)); err != nil {
			return
		}
		pl, err := br.ReadByte()
		if err != nil {
			return
		}
		pw := make([]byte, pl)
		if _, err := io.ReadFull(br, pw); err != nil {
			return
		}
		if subtle.ConstantTimeCompare(pw, []byte(p.password)) != 1 {
			conn.Write([]byte{1, 1})
			return
		}
		if _, err := conn.Write([]byte{1, 0}); err != nil {
			return
		}
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil {
		return
	}
	reply := func(code byte) {
		conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	}
	if req[0] != 5 {
		return
	}
	if req[1] != 1 { // CONNECT only
		reply(0x07)
		return
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 3:
		n, err := br.ReadByte()
		if err != nil || n == 0 {
			return
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = string(b)
	default:
		reply(0x08)
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(br, pb); err != nil {
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(pb[0])<<8|int(pb[1])))

	up, spare, row, err := p.dial(target, clientIP)
	if err != nil {
		reply(0x05)
		return
	}
	hosts := p.be.Hosts()
	defer p.finish(up, row)
	reply(0x00)
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			return
		}
		hosts.moved(row, 0, int64(len(spare)))
	}
	p.pipe(conn, br, up, row)
}
