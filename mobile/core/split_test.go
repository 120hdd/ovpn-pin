package core

import (
	"net/netip"
	"os"
	"path/filepath"
	"testing"
)

func TestNormalizeSplitEntry(t *testing.T) {
	good := map[string]string{
		"digikala.com":                   "digikala.com",
		"  https://WWW.Digikala.com/x?y": "www.digikala.com",
		"*.digikala.com":                 "digikala.com",
		".digikala.com":                  "digikala.com",
		"digikala.com.":                  "digikala.com",
		"digikala.com:443":               "digikala.com",
		"user@digikala.com":              "digikala.com",
		"ir":                             "ir",
		"ایران":                          "xn--mgba3a4f16a",
		"مثال.ایران":                     "xn--mgbh0fb.xn--mgba3a4f16a",
		"bücher.de":                      "xn--bcher-kva.de",
		"5.6.7.8":                        "5.6.7.8",
		"5.6.7.8:80":                     "5.6.7.8",
		"5.6.7.8/16":                     "5.6.0.0/16",
		"::ffff:5.6.7.8":                 "5.6.7.8",
	}
	for in, want := range good {
		got, err := NormalizeSplitEntry(in)
		if err != nil || got != want {
			t.Errorf("NormalizeSplitEntry(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"", "  ", "2001:db8::/32", "2001:db8::1", "a b.com",
		"-bad.com", "bad-.com", "*.", "http://", "x..com"} {
		if got, err := NormalizeSplitEntry(in); err == nil {
			t.Errorf("NormalizeSplitEntry(%q) = %q, want an error", in, got)
		}
	}
}

func TestSplitMatching(t *testing.T) {
	s, err := NewSplit(SplitBypass, false,
		[]string{"digikala.com", "5.6.0.0/16", "9.9.9.9", ""}, nil, "")
	if err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]bool{
		"digikala.com": true, "www.digikala.com": true, "a.b.digikala.com.": true,
		"WWW.DIGIKALA.COM": true, "notdigikala.com": false, "digikala.com.evil.net": false,
		"com": false, "": false,
	} {
		if got := s.NameListed(name); got != want {
			t.Errorf("NameListed(%q) = %v, want %v", name, got, want)
		}
	}
	for addr, want := range map[string]bool{
		"5.6.0.0": true, "5.6.255.255": true, "5.5.255.255": false, "5.7.0.0": false,
		"9.9.9.9": true, "9.9.9.10": false, "::ffff:5.6.1.1": true, "2001:db8::1": false,
	} {
		if got := s.AddrListed(netip.MustParseAddr(addr)); got != want {
			t.Errorf("AddrListed(%s) = %v, want %v", addr, got, want)
		}
	}
}

func TestSplitDirection(t *testing.T) {
	by, _ := NewSplit(SplitBypass, false, []string{"a.com"}, nil, "")
	only, _ := NewSplit(SplitOnly, false, []string{"a.com"}, []int{10123}, "")
	off, _ := NewSplit(SplitOff, true, []string{"a.com"}, nil, "")
	empty, _ := NewSplit(SplitOnly, false, nil, nil, "")

	if !by.Direct(true) || by.Direct(false) {
		t.Error("bypass: listed goes around, the rest through")
	}
	if only.Direct(true) || !only.Direct(false) {
		t.Error("only: listed goes through, the rest around")
	}
	if off.Direct(true) || off.Direct(false) || off.Active() {
		t.Error("off decides nothing")
	}
	if empty.Active() || empty.Direct(false) {
		t.Error("only with an empty list must carry everything, not nothing")
	}
	if !only.UidListed(10123) || only.UidListed(10124) || only.UidListed(-1) {
		t.Error("uids")
	}
	if !only.NeedsOwner() || by.NeedsOwner() {
		t.Error("an owner is only worth asking about when apps are listed")
	}
	// A name going around is looked up from here only in bypass: in only,
	// an unlisted name may belong to a listed app, and must not be forged.
	if !by.DirectDNS("x.a.com") || by.DirectDNS("b.com") || only.DirectDNS("b.com") {
		t.Error("DirectDNS")
	}
}

func TestSplitRefuses(t *testing.T) {
	if _, err := NewSplit("sometimes", false, nil, nil, ""); err == nil {
		t.Error("an unknown mode should be refused")
	}
	if _, err := NewSplit(SplitBypass, false, []string{"not a domain"}, nil, ""); err == nil {
		t.Error("an entry that cannot be a rule should be refused, not ignored")
	}
	if _, err := NewSplit(SplitBypass, false, nil, nil, "dns.example"); err == nil {
		t.Error("the direct resolver must be an address")
	}
	s, err := NewSplit(SplitBypass, false, []string{"a.com"}, nil, "192.168.1.1")
	if err != nil {
		t.Fatal(err)
	}
	if r, ok := s.Resolver(); !ok || r.String() != "192.168.1.1:53" {
		t.Errorf("Resolver() = %v, %v", r, ok)
	}
}

func TestIranPreset(t *testing.T) {
	s, err := NewSplit(SplitBypass, true, nil, nil, "")
	if err != nil {
		t.Fatal(err)
	}
	domains, ranges := IranCounts()
	if domains < 20 || ranges < 1000 {
		t.Fatalf("the preset looks empty: %d domains, %d ranges", domains, ranges)
	}
	for _, name := range []string{"shaparak.ir", "www.digikala.com", "aparat.com", "xn--mgba3a4f16a"} {
		if !s.NameListed(name) {
			t.Errorf("%s should be in the preset", name)
		}
	}
	for _, name := range []string{"google.com", "telegram.org", "irish.com"} {
		if s.NameListed(name) {
			t.Errorf("%s should not be in the preset", name)
		}
	}
	// 2.144.0.0/14 is one of Iran's oldest mobile blocks; 1.1.1.1 is not Iran.
	if !s.AddrListed(netip.MustParseAddr("2.144.1.1")) || s.AddrListed(netip.MustParseAddr("1.1.1.1")) {
		t.Error("address ranges")
	}
	if len(IranApps()) == 0 {
		t.Error("no preset apps")
	}
}

// The lists are kept in /lists for both apps and copied here only because Go
// embeds nothing from outside its module. A copy that drifted would have the
// phone and the desktop disagree about what is Iranian.
func TestListsMatchTheRepoCopy(t *testing.T) {
	for _, name := range []string{"iran-ipv4.txt", "iran-domains.txt", "iran-apps.txt"} {
		here, err := os.ReadFile(filepath.Join("lists", name))
		if err != nil {
			t.Fatal(err)
		}
		there, err := os.ReadFile(filepath.Join("..", "..", "lists", name))
		if err != nil {
			t.Skipf("no repo copy to compare with: %v", err)
		}
		if normalise(here) != normalise(there) {
			t.Errorf("mobile/core/lists/%s differs from lists/%s - run python lists/update.py", name, name)
		}
	}
}

// normalise ignores line endings, which git may have changed on checkout.
func normalise(b []byte) string {
	out := make([]byte, 0, len(b))
	for _, c := range b {
		if c != '\r' {
			out = append(out, c)
		}
	}
	return string(out)
}

func TestQuestionName(t *testing.T) {
	// A query for www.example.com, type A.
	q := []byte{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0,
		3, 'w', 'w', 'w', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
		0, 1, 0, 1}
	if got := questionName(q); got != "www.example.com" {
		t.Errorf("questionName = %q", got)
	}
	if got := questionName(q[:8]); got != "" {
		t.Errorf("a short message should have no name, got %q", got)
	}
}
