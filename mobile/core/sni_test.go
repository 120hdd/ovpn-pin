package core

import (
	"crypto/tls"
	"net"
	"testing"
	"time"
)

// A real ClientHello, produced by Go's own TLS rather than written by hand.
//
// A handshake typed out from the RFC tests the parser against my reading of
// the RFC. Letting crypto/tls send one and catching the bytes tests it
// against what a client actually puts on the wire - which is the thing it
// will be given.
func clientHello(t *testing.T, name string) []byte {
	t.Helper()

	client, server := net.Pipe()
	got := make(chan []byte, 1)

	go func() {
		buf := make([]byte, 4096)
		_ = server.SetReadDeadline(time.Now().Add(5 * time.Second))
		n, _ := server.Read(buf)
		got <- buf[:n]
		server.Close()
	}()

	go func() {
		c := tls.Client(client, &tls.Config{ServerName: name})
		_ = c.SetDeadline(time.Now().Add(5 * time.Second))
		// It will fail - nothing answers - and the bytes are already sent.
		_ = c.Handshake()
		client.Close()
	}()

	select {
	case b := <-got:
		return b
	case <-time.After(5 * time.Second):
		t.Fatal("no ClientHello came out of crypto/tls")
		return nil
	}
}

func TestReadsTheNameOutOfAClientHello(t *testing.T) {
	for _, name := range []string{
		"github.com",
		"www.cloudflare.com",
		"a-very-long-subdomain.example.co.uk",
	} {
		if got := sniFrom(clientHello(t, name)); got != name {
			t.Errorf("read %q, wanted %q", got, name)
		}
	}
}

// exit.go sends a handshake with no name in it on purpose. Reading one has to
// come back empty rather than come back wrong - a row labelled with whatever
// happened to be at that offset would be worse than one labelled by address.
func TestNamelessHandshakeReadsAsNothing(t *testing.T) {
	client, server := net.Pipe()
	got := make(chan []byte, 1)

	go func() {
		buf := make([]byte, 4096)
		_ = server.SetReadDeadline(time.Now().Add(5 * time.Second))
		n, _ := server.Read(buf)
		got <- buf[:n]
		server.Close()
	}()
	go func() {
		c := tls.Client(client, &tls.Config{InsecureSkipVerify: true})
		_ = c.SetDeadline(time.Now().Add(5 * time.Second))
		_ = c.Handshake()
		client.Close()
	}()

	if name := sniFrom(<-got); name != "" {
		t.Errorf("invented %q for a handshake that announced nothing", name)
	}
}

// Everything that is not a ClientHello, including every prefix of one. This
// runs on the first bytes of every connection the phone makes, so it has to
// be unshakeable rather than merely correct on good input.
func TestRubbishIsSurvived(t *testing.T) {
	hello := clientHello(t, "example.com")

	for i := 0; i <= len(hello); i++ {
		sniFrom(hello[:i])
	}
	for _, b := range [][]byte{
		nil,
		{},
		{0x16},
		{0x16, 0x03, 0x01, 0xFF, 0xFF},
		[]byte("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"),
		make([]byte, 4096),
	} {
		sniFrom(b)
	}

	// Lengths that claim more than is there - the shape a hostile or
	// truncated message takes.
	broken := append([]byte(nil), hello...)
	for i := 3; i < 45 && i < len(broken); i++ {
		was := broken[i]
		broken[i] = 0xFF
		sniFrom(broken)
		broken[i] = was
	}

	if sniFrom(hello) != "example.com" {
		t.Error("a good message stopped reading after the bad ones")
	}
}
