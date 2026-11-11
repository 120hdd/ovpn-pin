package core

import (
	"encoding/base64"
	"strings"
	"testing"
)

func key(json string) string {
	return KeyPrefix + base64.RawURLEncoding.EncodeToString([]byte(json))
}

func TestParseRelayKey(t *testing.T) {
	pin := strings.Repeat("ab", 32)
	k, err := ParseRelayKey(key(`{"v":1,"host":"203.0.113.7","pin":"` + strings.ToUpper(pin) +
		`","pw":"p1","apw":"a2","ss":{"method":"chacha20-ietf-poly1305","password":"s3","port":8388,"exitPort":8389}}`))
	if err != nil {
		t.Fatal(err)
	}
	if k.Host != "203.0.113.7" || k.Pin != pin || k.Password != "p1" || k.APIPassword != "a2" {
		t.Fatalf("read wrong: %+v", k)
	}
	if k.SS == nil || k.SS.Port != 8388 || k.SS.ExitPort != 8389 || k.SS.Password != "s3" {
		t.Fatalf("ss read wrong: %+v", k.SS)
	}

	// The same key as the installer's printf fallback writes it, padded and
	// wrapped by a chat app.
	padded := key(`{"v":1,"host":"relay.example.com","pin":"","pw":"p","apw":"a"}`) + "=="
	wrapped := padded[:20] + "\n  " + padded[20:]
	k, err = ParseRelayKey(wrapped)
	if err != nil || k.Host != "relay.example.com" || k.SS != nil {
		t.Fatalf("wrapped key: %+v %v", k, err)
	}
}

func TestParseRelayKeyPort(t *testing.T) {
	base := func(port string) string {
		return key(`{"v":1,"host":"203.0.113.7","pw":"p","apw":"a"` + port + `}`)
	}
	k, err := ParseRelayKey(base(`,"port":8443`))
	if err != nil || k.Port != 8443 {
		t.Fatalf("port 8443: %+v %v", k, err)
	}
	// No port is the installer's default, and must not read as zero.
	if k, err = ParseRelayKey(base(``)); err != nil || k.Port != 443 {
		t.Fatalf("no port: %+v %v", k, err)
	}
	for name, p := range map[string]string{
		"zero":     `0`,
		"too big":  `65536`,
		"negative": `-1`,
		"fraction": `8443.5`,
		"string":   `"8443"`,
		"bool":     `true`,
		"object":   `{}`,
	} {
		if _, err := ParseRelayKey(base(`,"port":` + p)); err == nil {
			t.Errorf("port %s (%s): accepted", name, p)
		}
	}
}

func TestParseRelayKeyRefuses(t *testing.T) {
	for name, text := range map[string]string{
		"empty":        "  ",
		"not base64":   "relay://!!!",
		"not json":     KeyPrefix + base64.RawURLEncoding.EncodeToString([]byte("hello")),
		"newer":        key(`{"v":2,"host":"h","pw":"p","apw":"a"}`),
		"no host":      key(`{"v":1,"pw":"p","apw":"a"}`),
		"no pw":        key(`{"v":1,"host":"h","apw":"a"}`),
		"bad host":     key(`{"v":1,"host":"h/../x","pw":"p","apw":"a"}`),
		"bad pin":      key(`{"v":1,"host":"h","pin":"xyz","pw":"p","apw":"a"}`),
		"bad method":   key(`{"v":1,"host":"h","pw":"p","apw":"a","ss":{"method":"rc4-md5","password":"s","port":1,"exitPort":2}}`),
		"bad port":     key(`{"v":1,"host":"h","pw":"p","apw":"a","ss":{"method":"aes-256-gcm","password":"s","port":0,"exitPort":2}}`),
		"no ss secret": key(`{"v":1,"host":"h","pw":"p","apw":"a","ss":{"method":"aes-256-gcm","password":"","port":1,"exitPort":2}}`),
	} {
		if _, err := ParseRelayKey(text); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
