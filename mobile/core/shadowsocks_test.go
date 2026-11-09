package core

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	sscore "github.com/shadowsocks/go-shadowsocks2/core"
	"github.com/shadowsocks/go-shadowsocks2/socks"
)

// echoServer answers every connection with what it was sent.
func echoServer(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	return ln.Addr().String()
}

// ssServer starts a minimal Shadowsocks server - read the address, dial it,
// relay, the same thing gost's ss handler does - in a process of its own.
//
// Its own, because go-shadowsocks2 keeps one replay filter per process: the
// client side adds every salt it sends and the server side refuses any salt
// already in there, so a client and a server sharing a process refuse each
// other. Two processes is also simply what it is in use.
func ssServer(t *testing.T, method, password string) (string, int) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestHelperSSServer$")
	cmd.Env = append(os.Environ(), "RELAY_SS_HELPER=1",
		"RELAY_SS_METHOD="+method, "RELAY_SS_PASSWORD="+password)
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cmd.Process.Kill(); cmd.Wait() })
	sc := bufio.NewScanner(out)
	for sc.Scan() {
		if addr, ok := strings.CutPrefix(sc.Text(), "LISTENING "); ok {
			host, port, _ := net.SplitHostPort(addr)
			p, _ := strconv.Atoi(port)
			return host, p
		}
	}
	t.Fatal("the helper server never said where it listens")
	return "", 0
}

// TestHelperSSServer is not a test: it is the far end ssServer starts.
func TestHelperSSServer(t *testing.T) {
	if os.Getenv("RELAY_SS_HELPER") == "" {
		t.Skip("only run as ssServer's helper process")
	}
	ciph, err := sscore.PickCipher(os.Getenv("RELAY_SS_METHOD"), nil,
		os.Getenv("RELAY_SS_PASSWORD"))
	if err != nil {
		t.Fatal(err)
	}
	ln, err := sscore.Listen("tcp", "127.0.0.1:0", ciph)
	if err != nil {
		t.Fatal(err)
	}
	fmt.Printf("LISTENING %s\n", ln.Addr())
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			tgt, err := socks.ReadAddr(c)
			if err != nil {
				return
			}
			up, err := net.Dial("tcp", tgt.String())
			if err != nil {
				return
			}
			defer up.Close()
			go io.Copy(up, c)
			io.Copy(c, up)
		}()
	}
}

func roundTrip(t *testing.T, w Way, target string) {
	t.Helper()
	conn, spare, err := w.Open(target, 5*time.Second)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer conn.Close()
	if len(spare) != 0 {
		t.Fatalf("Shadowsocks has no answer to hand back, got %d bytes", len(spare))
	}
	msg := bytes.Repeat([]byte("relay-ss-"), 4000) // several AEAD chunks
	go conn.Write(msg)
	got := make([]byte, len(msg))
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read back: %v", err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatal("what came back is not what was sent")
	}
}

func TestShadowsocksRoundTrip(t *testing.T) {
	for _, method := range SSMethods {
		t.Run(method, func(t *testing.T) {
			host, port := ssServer(t, method, "correct horse")
			roundTrip(t, NewShadowsocksServer(host, port, method, "correct horse"), echoServer(t))
		})
	}
}

func TestShadowsocksWrongPassword(t *testing.T) {
	host, port := ssServer(t, "chacha20-ietf-poly1305", "right")
	w := NewShadowsocksServer(host, port, "chacha20-ietf-poly1305", "wrong")
	conn, _, err := w.Open(echoServer(t), 3*time.Second)
	if err != nil {
		return // refused outright: fine
	}
	defer conn.Close()
	conn.Write([]byte("hello"))
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if n, _ := conn.Read(make([]byte, 5)); n > 0 {
		t.Fatal("a server with another key carried traffic")
	}
}

func TestShadowsocksBadMethod(t *testing.T) {
	w := NewShadowsocksServer("127.0.0.1", 1, "rc4-md5", "x")
	if _, _, err := w.Open("example.com:443", time.Second); err == nil {
		t.Fatal("a method nobody agreed on was accepted")
	}
}

// Against a real server - sing-box or gost - when one is named, e.g.
//
//	RELAY_TEST_SS=127.0.0.1:18388 RELAY_TEST_SS_PASSWORD=... go test -run Live
func TestShadowsocksLive(t *testing.T) {
	addr := os.Getenv("RELAY_TEST_SS")
	if addr == "" {
		t.Skip("RELAY_TEST_SS not set")
	}
	host, port, _ := net.SplitHostPort(addr)
	p, _ := strconv.Atoi(port)
	roundTrip(t, NewShadowsocksServer(host, p, "chacha20-ietf-poly1305",
		os.Getenv("RELAY_TEST_SS_PASSWORD")), echoServer(t))
}
