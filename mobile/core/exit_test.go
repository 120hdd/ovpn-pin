package core

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The whole design rests on one claim that is invisible when it holds and
// silent when it breaks: InsecureSkipVerify is set, and the checking it
// turned off is done by hand instead. A verify() that returned nil early
// would pass every test that only asks whether connecting works - and would
// have thrown away the certificate check on every exit.
//
// So this asks the opposite question. Same live address, same live
// certificate, a name that address does not serve. If the connection is
// allowed, the hand verification is not running.
func TestWrongNameIsRefused(t *testing.T) {
	s, user, password := liveExit(t)

	e := NewExit(s.Addr, "www.example.com", user, password)
	conn, err := e.Dial(4 * time.Second)
	if err == nil {
		conn.Close()
		t.Fatalf("%s was accepted as www.example.com - the certificate check "+
			"that InsecureSkipVerify turned off is not being done by hand",
			s.Addr)
	}
	if !strings.Contains(err.Error(), "does not serve") {
		t.Fatalf("refused, but for the wrong reason: %v", err)
	}
}

// And the other half: the same address under the name it was pinned from has
// to be accepted, or the test above would pass for a connection that never
// works at all.
func TestPinnedNameIsAccepted(t *testing.T) {
	s, user, password := liveExit(t)

	e := NewExit(s.Addr, s.Name, user, password)
	conn, err := e.Dial(6 * time.Second)
	if err != nil {
		if errors.Is(err, ErrFiltered) {
			t.Skipf("%s is filtered on this line", s.Addr)
		}
		t.Fatalf("%s was refused under its own name %s: %v", s.Addr, s.Name, err)
	}
	conn.Close()
}

// A pinned config that still holds a name rather than an address is the one
// thing ReadConfig must never quietly accept: resolving it here would use the
// phone's resolver, which is what this repo exists to stop trusting.
func TestUnpinnedConfigIsRefused(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "unpinned.ovpn")
	body := "client\nremote de-fra.prod.surfshark.com 1443 tcp\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadConfig(path); err == nil {
		t.Fatal("a config whose remote is still a name was accepted as pinned")
	}
}

// liveExit finds a pinned config and the credentials for it, or skips. These
// two tests measure the real thing on the real line on purpose - a stub
// certificate would prove the test harness works and nothing about the exits.
func liveExit(t *testing.T) (Server, string, string) {
	t.Helper()

	root := filepath.Join("..", "..")
	user, password, err := Credentials(filepath.Join(root, ".ovpn-auth"))
	if err != nil {
		t.Skipf("no credentials to test with: %v", err)
	}

	paths, _ := filepath.Glob(filepath.Join(root, "pinned", "*.surfshark.com*.ovpn"))
	for _, p := range paths {
		s, err := ReadConfig(p)
		if err != nil || s.Name == "" {
			continue
		}
		// Reachable first, so a filtered address does not read as a
		// verification failure in either direction.
		e := NewExit(s.Addr, s.Name, user, password)
		conn, err := e.Dial(4 * time.Second)
		if err != nil {
			continue
		}
		conn.Close()
		return s, user, password
	}
	t.Skip("no pinned exit answered on this line")
	return Server{}, "", ""
}
