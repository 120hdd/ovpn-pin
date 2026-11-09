package core

import (
	"os"
	"regexp"
	"testing"
)

// Every country the apps can name is in a group of the list, so none falls
// into "Elsewhere" at the bottom by accident.
func TestEveryCountryHasAContinent(t *testing.T) {
	src, err := os.ReadFile("countries.go")
	if err != nil {
		t.Fatal(err)
	}
	for _, code := range regexp.MustCompile(`"([a-z]{2})"\s*:`).FindAllStringSubmatch(string(src), -1) {
		if ContinentOf(code[1]) == "Elsewhere" {
			t.Errorf("%s has no continent", code[1])
		}
	}
	if ContinentOf("IR") != "Middle East" || ContinentOf("de") != "Europe" || ContinentOf("zz") != "Elsewhere" {
		t.Error("ContinentOf")
	}
}
