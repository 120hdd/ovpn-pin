package core

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"strings"
)

// The key the server's installer prints: relay:// and then base64url of a
// JSON object holding the server, its certificate's pin when it is reached
// by address, both passwords and - when the server offers it - Shadowsocks.
// Pasted into the app, it is the whole of setting a server up.
//
// The desktop reads the same key in app/tunnel.py (parse_key); the two
// check the same things and refuse the same keys.

// KeyPrefix starts every key.
const KeyPrefix = "relay://"

// RelayKey is a key, read and checked.
type RelayKey struct {
	Host        string      `json:"host"`
	Pin         string      `json:"pin"`
	Password    string      `json:"password"`
	APIPassword string      `json:"apiPassword"`
	SS          *RelayKeySS `json:"ss,omitempty"`
	// The locations the server keeps for other devices, when it has them.
	// See export.go.
	Export *RelayKeyExport `json:"export,omitempty"`
}

// RelayKeySS is the server's Shadowsocks: one key, two ports - Port exits at
// the server, ExitPort at the provider location the app has chosen.
type RelayKeySS struct {
	Method   string `json:"method"`
	Password string `json:"password"`
	Port     int    `json:"port"`
	ExitPort int    `json:"exitPort"`
}

// SSMethods are the AEAD methods the server and both apps agree on.
var SSMethods = []string{"chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm"}

// ParseRelayKey reads a key and checks every field, because it arrives
// pasted out of a chat message and a value that is not what it claims would
// otherwise turn up much later, as a connection that fails for no reason the
// window can name.
func ParseRelayKey(text string) (*RelayKey, error) {
	raw := strings.TrimSpace(text)
	if strings.HasPrefix(strings.ToLower(raw), KeyPrefix) {
		raw = raw[len(KeyPrefix):]
	}
	// Chat apps wrap long lines; a key is one token, so whitespace is noise.
	raw = strings.Join(strings.Fields(raw), "")
	if raw == "" {
		return nil, errors.New("that is empty")
	}
	body, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(raw, "="))
	if err != nil {
		return nil, errors.New("that is not a Relay key - it should start with relay://")
	}
	var wire struct {
		V   int    `json:"v"`
		Host string `json:"host"`
		Pin  string `json:"pin"`
		PW   string `json:"pw"`
		APW  string `json:"apw"`
		SS   *struct {
			Method   string `json:"method"`
			Password string `json:"password"`
			Port     int    `json:"port"`
			ExitPort int    `json:"exitPort"`
		} `json:"ss"`
		X *struct {
			ID string `json:"id"`
			N  int    `json:"n"`
		} `json:"x"`
	}
	if err := json.Unmarshal(body, &wire); err != nil {
		return nil, errors.New("that is not a Relay key - it should start with relay://")
	}
	if wire.V != 1 {
		return nil, errors.New("that key is from a newer Relay than this one")
	}
	k := &RelayKey{
		Host:        strings.TrimSpace(wire.Host),
		Pin:         strings.ToLower(strings.TrimSpace(wire.Pin)),
		Password:    strings.TrimSpace(wire.PW),
		APIPassword: strings.TrimSpace(wire.APW),
	}
	switch {
	case k.Host == "":
		return nil, errors.New("the key has no host")
	case k.Password == "":
		return nil, errors.New("the key has no pw")
	case k.APIPassword == "":
		return nil, errors.New("the key has no apw")
	case !validHost(k.Host):
		return nil, errors.New("the server in that key is not an address or a name")
	case k.Pin != "" && !isHex64(k.Pin):
		return nil, errors.New("the certificate pin in that key is malformed")
	}
	if wire.SS != nil {
		ss := wire.SS
		ok := ss.Password != "" && validPort(ss.Port) && validPort(ss.ExitPort)
		known := false
		for _, m := range SSMethods {
			known = known || ss.Method == m
		}
		if !ok || !known {
			return nil, errors.New("the key's Shadowsocks settings are malformed")
		}
		k.SS = &RelayKeySS{Method: ss.Method, Password: ss.Password,
			Port: ss.Port, ExitPort: ss.ExitPort}
	}
	if wire.X != nil {
		if !validExportID(wire.X.ID) || wire.X.N < 1 || wire.X.N > MaxExportSlots {
			return nil, errors.New("the key's exported locations are malformed")
		}
		k.Export = &RelayKeyExport{ID: wire.X.ID, Slots: wire.X.N}
	}
	return k, nil
}

func validPort(p int) bool { return p > 0 && p < 65536 }

func validHost(h string) bool {
	if ip := net.ParseIP(h); ip != nil && ip.To4() != nil {
		return true
	}
	for _, part := range strings.Split(h, ".") {
		if part == "" || len(part) > 63 {
			return false
		}
		for _, r := range part {
			if !(r == '-' || r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z') {
				return false
			}
		}
	}
	return true
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, r := range s {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}
