package core

import (
	"encoding/json"
	"path/filepath"
	"testing"
)

// The window groups by country, so the grouping is the thing worth testing: a
// folder of real configs has to come back as the countries a person would
// recognise, with every exit accounted for in exactly one of them.
func TestCatalogueOverRealConfigs(t *testing.T) {
	cat, err := Scan(filepath.Join("..", "..", "pinned"))
	if err != nil {
		t.Skipf("no pinned folder here: %v", err)
	}
	if cat.Count() == 0 {
		t.Skip("nothing pinned")
	}

	var rows []map[string]any
	if err := json.Unmarshal([]byte(cat.CountriesJSON()), &rows); err != nil {
		t.Fatalf("the window would not be able to read this: %v", err)
	}
	if len(rows) == 0 {
		t.Fatal("servers, but no countries")
	}

	total := 0
	for _, r := range rows {
		n := int(r["count"].(float64))
		if n == 0 {
			t.Errorf("%v is in the list with no exits in it", r["code"])
		}
		total += n
		// A code that never got a name is the failure that looks like a
		// working app: the row renders, it just says "AD" forever.
		if r["name"] == r["code"] {
			t.Errorf("%v has no country name", r["code"])
		}
		cities, _ := r["cityList"].([]any)
		if len(cities) == 0 {
			t.Errorf("%v has no cities, so opening it would show nothing", r["code"])
		}
	}
	if total != cat.Count() {
		t.Errorf("%d exits scanned but %d counted across countries",
			cat.Count(), total)
	}
	t.Logf("%d exits in %d countries", cat.Count(), len(rows))
}

// uk and gb are one country. Both providers are in the folder and they
// disagree about which to write, and a list showing the United Kingdom twice
// is the thing Canon exists to prevent.
func TestOneUnitedKingdom(t *testing.T) {
	if Canon("gb") != "uk" {
		t.Fatalf("gb folded to %q, not uk", Canon("gb"))
	}
	if CountryName("gb") != CountryName("uk") {
		t.Fatalf("%q and %q are meant to be one country",
			CountryName("gb"), CountryName("uk"))
	}
}

// Both providers are named from the filename alone, because opening 147 files
// to learn what 147 names already say is a second of a phone's time for
// nothing.
func TestProviderComesFromTheName(t *testing.T) {
	cases := map[string]string{
		"ad-leu.prod.surfshark.com_tcp_62.197.152.149.ovpn":    "surfshark",
		"ae-dub.ws.ae-003.totallyacdn.com_146.70.191.194.ovpn": "windscribe",
		"01.8s-fr-par.ws.fr-030.totallyacdn.com_1.2.3.4.ovpn":  "windscribe",
	}
	for name, want := range cases {
		if got := providerOf(name); got != want {
			t.Errorf("%s read as %s, not %s", name, got, want)
		}
	}
}
