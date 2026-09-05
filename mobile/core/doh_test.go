package core

import (
	"context"
	"net/netip"
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
