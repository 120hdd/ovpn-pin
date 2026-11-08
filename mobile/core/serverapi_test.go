package core

import (
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

// A real server reached by IP, through the real line - skipped unless given:
//
//	RELAY_TEST_SERVER=46.29.34.53 RELAY_TEST_PASSWORD=... RELAY_TEST_API=... go test -run ServerByAddress -v
func TestServerByAddress(t *testing.T) {
	host := os.Getenv("RELAY_TEST_SERVER")
	if host == "" {
		t.Skip("RELAY_TEST_SERVER not set")
	}
	pw := os.Getenv("RELAY_TEST_PASSWORD")

	// First connect: no pin yet, so the certificate is taken and reported.
	srv := NewTunnelServer(host, pw, nil, ModeSingle)
	out, err := SeenAsThrough(srv, 20*time.Second)
	srv.Close()
	if err != nil {
		t.Fatalf("through %s: %v", host, err)
	}
	pin := srv.SeenPin()
	if len(pin) != 64 {
		t.Fatalf("no pin seen: %q", pin)
	}
	t.Logf("seen as %s, pin %s", out, pin)

	// Pinned: the same certificate passes, any other is refused.
	ok := NewTunnelServer(host, pw, nil, ModeSingle)
	ok.Pin = pin
	if _, err := SeenAsThrough(ok, 20*time.Second); err != nil {
		t.Fatalf("pinned: %v", err)
	}
	ok.Close()
	bad := NewTunnelServer(host, pw, nil, ModeSingle)
	bad.Pin = strings.Repeat("0", 64)
	if _, err := SeenAsThrough(bad, 20*time.Second); err == nil || !errors.Is(err, ErrPinChanged) && !strings.Contains(err.Error(), ErrPinChanged.Error()) {
		t.Fatalf("a wrong pin was not refused: %v", err)
	}
	bad.Close()

	// The API answers with the right password and refuses a wrong one.
	if api := os.Getenv("RELAY_TEST_API"); api != "" {
		if err := SetExit(host, "wrong", pin, "146.70.194.221", "CHANGEME", "CHANGEME", "", 10*time.Second); err == nil {
			t.Fatal("a wrong API password was accepted")
		}
		if err := SetExit(host, api, pin, "146.70.194.221", "CHANGEME", "CHANGEME", "", 10*time.Second); err != nil {
			t.Fatalf("set exit: %v", err)
		}
	}
}

// The upload test tells a throttled exit from a free one, through the server:
//
//	RELAY_TEST_SERVER=… RELAY_TEST_PASSWORD=… RELAY_TEST_API=… RELAY_TEST_PIN=…
//	RELAY_TEST_EXITS=141.255.162.194,31.7.62.50 RELAY_TEST_EXIT_USER=… RELAY_TEST_EXIT_PASS=…
//	go test -run UploadThroughExits -v
func TestUploadThroughExits(t *testing.T) {
	host, exits := os.Getenv("RELAY_TEST_SERVER"), os.Getenv("RELAY_TEST_EXITS")
	if host == "" || exits == "" {
		t.Skip("RELAY_TEST_SERVER or RELAY_TEST_EXITS not set")
	}
	for _, ip := range strings.Split(exits, ",") {
		if err := SetExit(host, os.Getenv("RELAY_TEST_API"), os.Getenv("RELAY_TEST_PIN"), ip,
			os.Getenv("RELAY_TEST_EXIT_USER"), os.Getenv("RELAY_TEST_EXIT_PASS"), "", 15*time.Second); err != nil {
			t.Fatalf("set %s: %v", ip, err)
		}
		srv := NewTunnelServer(host, os.Getenv("RELAY_TEST_PASSWORD"), nil, ModeMulti)
		srv.Pin = os.Getenv("RELAY_TEST_PIN")
		rate, err := UploadRate(srv, 256<<10, 4*time.Second)
		srv.Close()
		t.Logf("%-16s up %.0f KB/s (err %v)", ip, rate/1024, err)
	}
}
