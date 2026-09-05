package core

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// ProxyPort is where these exits run their HTTPS proxy. The same machines
// answer OpenVPN on 1443; this is the port the line cannot tell from any
// other TLS connection.
const ProxyPort = 443

var headEnd = []byte("\r\n\r\n")

// Set on Android so the connection to the exit is kept out of the tun this
// process is serving. Nil everywhere else, which net.Dialer takes to mean an
// ordinary dial. See protect.go for why it exists at all.
var dialControl func(network, address string, c syscall.RawConn) error

// Exit is one server, ready to be dialled.
type Exit struct {
	Addr string // pinned IPv4
	Name string // what the certificate must serve
	Port int
	auth string // base64 of user:password, precomputed
}

func NewExit(addr, name, user, password string) *Exit {
	return &Exit{
		Addr: addr,
		Name: name,
		Port: ProxyPort,
		auth: base64.StdEncoding.EncodeToString([]byte(user + ":" + password)),
	}
}

// ErrFiltered is the third outcome, and worth telling apart from the other
// two. TCP completes at the normal round trip and then the TLS handshake is
// answered by nobody - the server is not down, because reached through
// another exit the same address completes TLS immediately. Something between
// here and it takes the SYN and then swallows the payload, so this address is
// filtered on this line and no amount of retrying will change it. Re-pin the
// exit and take a different address for it.
var ErrFiltered = errors.New("filtered here - TCP answers, TLS gets nothing back")

// Dial opens the TLS connection to the exit: to the address, without the
// name, proving the name anyway.
//
// This function is the reason the core is Go. Three settings have to hold at
// once and no two of them are usually allowed together:
//
//	ServerName: ""            nothing is announced. A handshake that names
//	                          *.prod.surfshark.com in the clear is killed on
//	                          the way out, by the same inspection that makes
//	                          the tunnel unusable.
//
//	InsecureSkipVerify: true  not what it sounds like, and it is the only way
//	                          to reach the callback below. With an empty
//	                          ServerName, crypto/tls refuses to run at all
//	                          unless this is set - so it is set, and every
//	                          check it turns off is done by hand underneath.
//
//	VerifyPeerCertificate     the chain still has to reach a system root, and
//	                          the leaf still has to serve the real name. The
//	                          name is proved, it is just never sent.
//
// tls.Client rather than tls.Dial on purpose: tls.Dial fills ServerName in
// from the address it was given, which would put the exit's IP in the SNI
// extension and undo the first setting without saying so.
func (e *Exit) Dial(timeout time.Duration) (*tls.Conn, error) {
	deadline := time.Now().Add(timeout)

	// Control rather than a plain DialTimeout, because on Android this is
	// where the socket has to be marked as not belonging inside the tunnel.
	// See SetSocketProtector; on every other platform dialControl is nil and
	// this is an ordinary dial.
	dialer := net.Dialer{Timeout: timeout, Control: dialControl}
	raw, err := dialer.Dial("tcp", net.JoinHostPort(e.Addr, strconv.Itoa(e.Port)))
	if err != nil {
		return nil, err
	}
	if tcp, ok := raw.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	conn := tls.Client(raw, &tls.Config{
		ServerName:            "",
		InsecureSkipVerify:    true,
		VerifyPeerCertificate: e.verify,
	})
	_ = conn.SetDeadline(deadline)
	if err := conn.Handshake(); err != nil {
		conn.Close()
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, ErrFiltered
		}
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// verify is what InsecureSkipVerify turned off, done again by hand.
//
// Both halves matter and they fail differently, so they are kept apart: a
// chain that does not reach a root means the answer came from something that
// is not the exit, and a chain that verifies but serves the wrong name means
// this address is no longer the exit it was pinned as. Collapsing them into
// one error would make the second look like the first.
func (e *Exit) verify(rawCerts [][]byte, _ [][]*x509.Certificate) error {
	if len(rawCerts) == 0 {
		return errors.New("the exit offered no certificate")
	}
	certs := make([]*x509.Certificate, len(rawCerts))
	for i, der := range rawCerts {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return fmt.Errorf("unreadable certificate from %s: %w", e.Addr, err)
		}
		certs[i] = c
	}

	// Everything after the leaf is offered as a route to a root, not trusted
	// as one. Roots stay nil, which means the platform store - Android reads
	// /system/etc/security/cacerts, iOS goes through Security.framework, and
	// on a desktop it is whatever the machine already trusts.
	inter := x509.NewCertPool()
	for _, c := range certs[1:] {
		inter.AddCert(c)
	}
	if _, err := certs[0].Verify(x509.VerifyOptions{
		Intermediates: inter,
		// Deliberately empty. The name is checked below instead, so that a
		// chain failure and a name failure arrive as different sentences.
		DNSName: "",
	}); err != nil {
		return fmt.Errorf("the certificate at %s does not verify: %w", e.Addr, err)
	}

	if e.Name == "" {
		return fmt.Errorf("%s was pinned without recording a name, so there is "+
			"nothing to check the certificate against", e.Addr)
	}
	// x509's own rule, which is the one OpenSSL would have applied: a
	// wildcard covers one label and no more.
	if err := certs[0].VerifyHostname(e.Name); err != nil {
		return fmt.Errorf("the certificate at %s does not serve %s", e.Addr, e.Name)
	}
	return nil
}

// Open asks the exit for a tunnel to target ("host:port") and hands back the
// connection with the CONNECT already answered, along with anything that
// arrived behind the answer.
//
// A CONNECT tunnel consumes its connection: once the exit answers 200 there
// is no framing to go back to, so this is never returned to a pool.
func (e *Exit) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	conn, err := e.Dial(timeout)
	if err != nil {
		return nil, nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	spare, err := e.connect(conn, target)
	if err != nil {
		conn.Close()
		return nil, nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, spare, nil
}

// connect sends the CONNECT and reads exactly the head of the answer,
// returning anything that arrived behind it.
//
// Read by hand rather than with http.ReadResponse, and this was measured
// rather than preferred: a 200 to CONNECT carries no Content-Length and no
// chunked encoding, so net/http types the body as "read until the connection
// closes" - and Body.Close() then drains it. Against a live exit that meant
// waiting out the whole timeout: 8.34s, where the Python asking the same
// question answered in 0.53s. The slowness was the visible half. The other
// half is that the bytes it drained belong to the tunnel.
func (e *Exit) connect(conn *tls.Conn, target string) ([]byte, error) {
	req := "CONNECT " + target + " HTTP/1.1\r\n" +
		"Host: " + target + "\r\n" +
		"Proxy-Authorization: Basic " + e.auth + "\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		return nil, err
	}

	var head []byte
	buf := make([]byte, 4096)
	for !bytes.Contains(head, headEnd) {
		n, err := conn.Read(buf)
		head = append(head, buf[:n]...)
		if err != nil {
			return nil, fmt.Errorf("the exit closed the connection on CONNECT: %w", err)
		}
		if len(head) > 32768 {
			return nil, errors.New("the exit answered CONNECT with nothing usable")
		}
	}

	end := bytes.Index(head, headEnd)
	spare := append([]byte(nil), head[end+len(headEnd):]...)
	status := string(bytes.SplitN(head[:end], []byte("\r\n"), 2)[0])

	code := ""
	if parts := strings.SplitN(status, " ", 3); len(parts) > 1 {
		code = parts[1]
	}
	switch {
	case code == "407":
		// Said plainly rather than as a status. On these exits it does not
		// mean the password is wrong - it means the account has no proxy on
		// it, which is a different thing to go and fix.
		return nil, errors.New("no proxy for this account")
	case !strings.HasPrefix(code, "2"):
		return nil, fmt.Errorf("CONNECT refused: %s", strings.TrimSpace(status))
	}
	return spare, nil
}
