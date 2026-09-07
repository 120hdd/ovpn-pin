package core

import (
	"encoding/json"
	"os"
	"regexp"
	"strings"
)

// The names a config goes by, and what a published list said about it.
//
// A filename is rewritten twice on its way through this app - a measured
// time goes on the front, a pinned address goes on the back - so three
// strings name one exit and each of them is somebody's key. These are the
// desktop's three, under the desktop's names, because both halves file things
// under them and have to agree about what they mean.

var (
	stemHead = regexp.MustCompile(`^\d+\.\d+s-`)
	stemTail = regexp.MustCompile(`_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$`)
	notWord  = regexp.MustCompile(`[^a-z0-9]`)
)

// ConfigStem is what survives pinning and sweeping - the middle, once a time
// has come off the front and an address off the back. windscribe.py's
// config_stem, and the key the notes are filed under.
func ConfigStem(name string) string {
	name = stemHead.ReplaceAllString(name, "")
	name = stemTail.ReplaceAllString(name, "")
	return strings.TrimSuffix(name, ".ovpn")
}

// BaseName is the name without the handshake time, so the same config swept
// out of two folders is one config. sweep.py's base_name.
func BaseName(name string) string { return stemHead.ReplaceAllString(name, "") }

// CityKey is one key per city, whatever each provider's filename calls it.
//
// Warsaw is waw to one and war to the other; Prague prg and pra. The name is
// the thing they agree on, so the name is the identity - squashed to letters
// and digits because it has to survive being a code in a picker.
// engine.py's city_key.
func CityKey(name string) string {
	key := notWord.ReplaceAllString(strings.ToLower(name), "")
	if key == "" {
		return "x"
	}
	return key
}

// IsWindscribe is the whole of what tells the two providers apart, and it is
// in the name rather than in the file.
func IsWindscribe(name string) bool { return strings.Contains(name, windscribeMark) }

// WsNote is what the published Windscribe list said about one exit.
//
// The numbers are pointers because the notes come from a fetch that may never
// have happened, and a missing load is not a load of zero - the page draws
// the two differently and is right to.
type WsNote struct {
	City    string `json:"city"`
	Nick    string `json:"nick"`
	Load    *int   `json:"load"`
	Gbps    *int   `json:"gbps"`
	P2P     *bool  `json:"p2p"`
	Premium *bool  `json:"premium"`
	Host    string `json:"host"`
}

// SsNote is the same for Surfshark, whose published list says less.
type SsNote struct {
	Place   string `json:"place"`
	Country string `json:"country"`
	Load    *int   `json:"load"`
}

// LoadWindscribeMeta reads the sidecar, keyed by ConfigStem.
//
// A missing or unreadable file is an empty map rather than an error: the
// notes decorate a list that works without them, and a phone that has never
// fetched a server list should show its exits by code rather than refuse to
// show them at all.
func LoadWindscribeMeta() map[string]WsNote {
	out := map[string]WsNote{}
	b, err := os.ReadFile(WindscribeMetaPath())
	if err != nil {
		return out
	}
	_ = json.Unmarshal(b, &out)
	return out
}

// LoadSurfsharkMeta reads Surfshark's sidecar, which keys by hostname and
// wraps its rows beside the time they were fetched.
func LoadSurfsharkMeta() map[string]SsNote {
	var wrapper struct {
		Fetched int64             `json:"fetched"`
		Servers map[string]SsNote `json:"servers"`
	}
	b, err := os.ReadFile(SurfsharkMetaPath())
	if err != nil {
		return map[string]SsNote{}
	}
	if err := json.Unmarshal(b, &wrapper); err != nil || wrapper.Servers == nil {
		return map[string]SsNote{}
	}
	return wrapper.Servers
}
