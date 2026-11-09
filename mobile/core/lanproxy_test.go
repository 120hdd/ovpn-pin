package core

import (
	"bufio"
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"
)

// fakeLAN is a backend whose "way out" is a function: Open hands back one
// end of a pipe and runs serve on the other, so a test can play the far
// server and see what was asked of it.
type fakeLAN struct {
	hosts    *Hosts
	serve    func(target string, c net.Conn)
	straight bool
	targets  chan string
}

func newFakeLAN(serve func(string, net.Conn)) *fakeLAN {
	return &fakeLAN{hosts: newHosts(), serve: serve, targets: make(chan string, 8)}
}

func (f *fakeLAN) Open(target string, _ time.Duration) (net.Conn, []byte, error) {
	f.targets <- target
	a, b := net.Pipe()
	go f.serve(target, b)
	return a, nil, nil
}
func (f *fakeLAN) Straight(string) bool { return f.straight }
func (f *fakeLAN) DialStraight(_ context.Context, target string, _ time.Duration) (net.Conn, error) {
	f.targets <- "straight:" + target
	a, b := net.Pipe()
	go f.serve(target, b)
	return a, nil
}
func (f *fakeLAN) Hosts() *Hosts { return f.hosts }

// An echo far end.
func echo(_ string, c net.Conn) { io.Copy(c, c); c.Close() }

func startLAN(t *testing.T, f *fakeLAN, password string, allow func(net.IP) bool) string {
	t.Helper()
	p := NewLANProxy(f, password)
	if allow != nil {
		p.allow = allow
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Serve(ln); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { p.Close() })
	return ln.Addr().String()
}

func anyone(net.IP) bool { return true }

func dialLAN(t *testing.T, addr string) net.Conn {
	t.Helper()
	c, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(5 * time.Second))
	t.Cleanup(func() { c.Close() })
	return c
}

func TestLANHTTPConnect(t *testing.T) {
	f := newFakeLAN(echo)
	addr := startLAN(t, f, "", anyone)
	c := dialLAN(t, addr)
	br := bufio.NewReader(c)

	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
	resp, err := http.ReadResponse(br, nil)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT: %v %v", resp, err)
	}
	io.WriteString(c, "ping")
	buf := make([]byte, 4)
	if _, err := io.ReadFull(br, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("echo through the tunnel: %q %v", buf, err)
	}
	if got := <-f.targets; got != "example.com:443" {
		t.Fatalf("opened %q", got)
	}
	// Counted, labelled with the client.
	f.hosts.mu.Lock()
	defer f.hosts.mu.Unlock()
	row := f.hosts.rows["example.com:443"]
	if row == nil || row.App == nil || !strings.HasPrefix(*row.App, "LAN ") || row.Up < 4 {
		t.Fatalf("hosts row: %+v", row)
	}
}

func TestLANHTTPPlainRewrittenToOriginForm(t *testing.T) {
	got := make(chan string, 1)
	f := newFakeLAN(func(_ string, c net.Conn) {
		defer c.Close()
		req, err := http.ReadRequest(bufio.NewReader(c))
		if err != nil {
			got <- "ERR " + err.Error()
			return
		}
		got <- req.Method + " " + req.RequestURI + " host=" + req.Host +
			" pc=" + req.Header.Get("Proxy-Connection") +
			" pa=" + req.Header.Get("Proxy-Authorization") +
			" close=" + map[bool]string{true: "y", false: "n"}[req.Close] +
			" x=" + req.Header.Get("X-Keep")
		io.WriteString(c, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
	})
	addr := startLAN(t, f, "", anyone)
	c := dialLAN(t, addr)

	io.WriteString(c, "GET http://example.com/a/b?q=1 HTTP/1.1\r\nHost: example.com\r\n"+
		"Proxy-Connection: keep-alive\r\nProxy-Authorization: Basic eDp5\r\nX-Keep: 1\r\n\r\n")
	body, _ := io.ReadAll(c)
	if !strings.Contains(string(body), "200 OK") || !strings.HasSuffix(string(body), "ok") {
		t.Fatalf("response: %q", body)
	}
	if want := "GET /a/b?q=1 host=example.com pc= pa= close=y x=1"; <-got != want {
		t.Fatalf("far end saw a different request; want %q", want)
	}
	if tg := <-f.targets; tg != "example.com:80" {
		t.Fatalf("opened %q, want example.com:80", tg)
	}
}

func TestLANSOCKS5Connect(t *testing.T) {
	f := newFakeLAN(echo)
	addr := startLAN(t, f, "", anyone)
	c := dialLAN(t, addr)

	c.Write([]byte{5, 1, 0})
	r := make([]byte, 2)
	if _, err := io.ReadFull(c, r); err != nil || r[0] != 5 || r[1] != 0 {
		t.Fatalf("method: %v %v", r, err)
	}
	name := "example.org"
	req := append([]byte{5, 1, 0, 3, byte(len(name))}, name...)
	req = append(req, 0x01, 0xbb) // 443
	c.Write(req)
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil || rep[1] != 0 {
		t.Fatalf("reply: %v %v", rep, err)
	}
	c.Write([]byte("hello"))
	buf := make([]byte, 5)
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("echo: %q %v", buf, err)
	}
	if got := <-f.targets; got != "example.org:443" {
		t.Fatalf("opened %q", got)
	}
}

func TestLANRefusesNonPrivateSource(t *testing.T) {
	f := newFakeLAN(echo)
	// The real filter, with the loopback test client it will not accept.
	addr := startLAN(t, f, "", PrivateSource)
	c := dialLAN(t, addr)
	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
	if n, _ := io.ReadAll(c); len(n) != 0 {
		t.Fatalf("a refused source got an answer: %q", n)
	}
	select {
	case tg := <-f.targets:
		t.Fatalf("a refused source reached the way out: %s", tg)
	default:
	}

	for ip, want := range map[string]bool{
		"10.1.2.3": true, "172.16.0.1": true, "172.31.255.1": true, "172.32.0.1": false,
		"192.168.1.5": true, "100.64.0.1": true, "100.127.1.1": true, "100.128.0.1": false,
		"169.254.9.9": true, "8.8.8.8": false, "127.0.0.1": false, "::1": false,
	} {
		if PrivateSource(net.ParseIP(ip)) != want {
			t.Errorf("PrivateSource(%s) != %v", ip, want)
		}
	}
}

func TestLANPasswordRequired(t *testing.T) {
	f := newFakeLAN(echo)
	addr := startLAN(t, f, "s3cret", anyone)

	// HTTP without it.
	c := dialLAN(t, addr)
	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil || resp.StatusCode != 407 {
		t.Fatalf("no password: %v %v", resp, err)
	}
	// Wrong one.
	c = dialLAN(t, addr)
	bad := base64.StdEncoding.EncodeToString([]byte("relay:nope"))
	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic "+bad+"\r\n\r\n")
	if resp, err = http.ReadResponse(bufio.NewReader(c), nil); err != nil || resp.StatusCode != 407 {
		t.Fatalf("wrong password: %v %v", resp, err)
	}
	// Right one.
	c = dialLAN(t, addr)
	good := base64.StdEncoding.EncodeToString([]byte("relay:s3cret"))
	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic "+good+"\r\n\r\n")
	if resp, err = http.ReadResponse(bufio.NewReader(c), nil); err != nil || resp.StatusCode != 200 {
		t.Fatalf("right password: %v %v", resp, err)
	}

	// SOCKS5: "no auth" is not offered an answer.
	c = dialLAN(t, addr)
	c.Write([]byte{5, 1, 0})
	r := make([]byte, 2)
	io.ReadFull(c, r)
	if r[1] != 0xff {
		t.Fatalf("socks without auth method: %v", r)
	}
	// Wrong password, then right.
	for _, tc := range []struct {
		pw   string
		want byte
	}{{"nope", 1}, {"s3cret", 0}} {
		c = dialLAN(t, addr)
		c.Write([]byte{5, 1, 2})
		io.ReadFull(c, r)
		if r[1] != 2 {
			t.Fatalf("method: %v", r)
		}
		msg := append([]byte{1, 1, 'u', byte(len(tc.pw))}, tc.pw...)
		c.Write(msg)
		io.ReadFull(c, r)
		if r[1] != tc.want {
			t.Fatalf("socks password %q: status %d, want %d", tc.pw, r[1], tc.want)
		}
	}
	for len(f.targets) > 1 {
		<-f.targets
	}
}

func TestLANStraightAndClose(t *testing.T) {
	f := newFakeLAN(echo)
	f.straight = true
	p := NewLANProxy(f, "")
	p.allow = anyone
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	p.Serve(ln)

	c := dialLAN(t, ln.Addr().String())
	io.WriteString(c, "CONNECT a.test:443 HTTP/1.1\r\nHost: a.test:443\r\n\r\n")
	if resp, err := http.ReadResponse(bufio.NewReader(c), nil); err != nil || resp.StatusCode != 200 {
		t.Fatalf("%v %v", resp, err)
	}
	if got := <-f.targets; got != "straight:a.test:443" {
		t.Fatalf("a destination split tunnelling sends around went %q", got)
	}
	if p.Clients() != 1 {
		t.Fatalf("clients = %d", p.Clients())
	}
	done := make(chan struct{})
	go func() { p.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Close did not return with a connection live")
	}
	if _, err := io.ReadAll(c); err != nil && !strings.Contains(err.Error(), "reset") {
		t.Logf("read after close: %v", err)
	}
	if _, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second); err == nil {
		t.Fatal("still listening after Close")
	}
}
