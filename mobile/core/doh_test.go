package core

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"testing"
	"time"
)

// The filter is the point of DoH here, not the encryption: an answer that
// comes back as a machine on your own LAN has to be refused rather than
// pinned. 10.10.34.35 is the address this repo was written against.
func TestForgedAddressesAreRefused(t *testing.T) {
	for _, s := range []string{"10.10.34.35", "127.0.0.1", "192.168.1.1", "0.0.0.0", "169.254.1.1"} {
		if IsPublic(netip.MustParseAddr(s)) {
			t.Errorf("%s was accepted as a public exit address", s)
		}
	}
	for _, s := range []string{"62.197.152.149", "146.70.160.237", "1.1.1.1"} {
		if !IsPublic(netip.MustParseAddr(s)) {
			t.Errorf("%s was refused, but it is a real public address", s)
		}
	}
}

// A forged answer has to stay tellable-apart by errors.Is, and now also has
// to carry what it was told, because the page prints the address.
//
// Both halves matter. Callers that only ask which kind of failure this was
// go through ErrForged and must not notice the change; the pin pane asks for
// the addresses and gets a category when the error came from somewhere that
// did not keep them.
func TestForgedErrorCarriesTheAddresses(t *testing.T) {
	err := error(&ForgedError{Addrs: []string{"10.10.34.36", "10.10.34.35"}})

	if !errors.Is(err, ErrForged) {
		t.Error("a ForgedError is no longer an ErrForged - every caller that " +
			"branches on the kind of failure has just stopped seeing this one")
	}
	if err.Error() != ErrForged.Error() {
		t.Errorf("it says %q, and ErrForged says %q", err, ErrForged)
	}

	var forged *ForgedError
	if !errors.As(err, &forged) {
		t.Fatal("errors.As did not unwrap it")
	}
	if got := strings.Join(forged.Addrs, ", "); got != "10.10.34.36, 10.10.34.35" {
		t.Errorf("the addresses came back as %q", got)
	}

	// A bare ErrForged is still an ErrForged and carries nothing, which is
	// the case pin.go falls back to its category for.
	if errors.As(ErrForged, &forged) {
		t.Error("a bare ErrForged unwrapped to a ForgedError")
	}
}

// And the live half: a real name, over DoH, through whatever route this
// machine has. Skipped rather than failed when nothing is reachable, so the
// suite still runs on a line that is down.
func TestResolveOverDoH(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	addrs, err := Resolve(ctx, nil, "cloudflare", "de-fra.prod.surfshark.com")
	if err != nil {
		t.Skipf("no DoH from here: %v", err)
	}
	if len(addrs) == 0 {
		t.Fatal("resolved, but with no addresses")
	}
	for _, a := range addrs {
		if !IsPublic(a) {
			t.Fatalf("a reserved address got through the filter: %s", a)
		}
	}
	t.Logf("de-fra.prod.surfshark.com -> %v", addrs)
}
