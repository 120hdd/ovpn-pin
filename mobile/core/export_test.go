package core

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

// The installer derives the same UUIDs in Python - uuid.uuid5(NAMESPACE_URL,
// "relay-export:<id>:<k>") - and these are its answers. A link whose UUID
// differs from the server's is a link that is refused, with nothing on the
// device to say why.
func TestSlotUUIDMatchesTheInstaller(t *testing.T) {
	for k, want := range map[int]string{
		0: "5e4d9bc1-037d-5cbf-898f-07a9277c12b6",
		1: "8facc91b-c776-5882-b569-860b4b664290",
		8: "c973411e-277a-5548-b54c-f06fa771b06a",
	} {
		if got := SlotUUID("abcDEF123", k); got != want {
			t.Errorf("SlotUUID(%d) = %s, want %s", k, got, want)
		}
	}
}

func TestExportLinksByName(t *testing.T) {
	links, err := ExportLinks(ExportServer{Host: "relay.example.com", Edge: "104.16.1.2", ID: "abcDEF123",
		SS: &RelayKeySS{Method: "chacha20-ietf-poly1305", Password: "p", Port: 8388, ExitPort: 8389}},
		[]ExportSlot{{0, "Relay · server"}, {1, "Relay · Germany"}})
	if err != nil {
		t.Fatal(err)
	}
	if len(links) != 2 {
		t.Fatalf("%d links", len(links))
	}

	u, err := url.Parse(links[1].VLESS)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if u.Scheme != "vless" || u.User.Username() != SlotUUID("abcDEF123", 1) ||
		u.Host != "104.16.1.2:443" || q.Get("sni") != "relay.example.com" ||
		q.Get("host") != "relay.example.com" || q.Get("type") != "ws" ||
		q.Get("path") != "/vl" || q.Get("security") != "tls" || q.Get("allowInsecure") != "" {
		t.Errorf("vless link: %s", links[1].VLESS)
	}
	if u.Fragment != "Relay · Germany" {
		t.Errorf("name: %q", u.Fragment)
	}

	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(links[1].VMess, "vmess://"))
	if err != nil {
		t.Fatal(err)
	}
	var vm map[string]string
	if err := json.Unmarshal(raw, &vm); err != nil {
		t.Fatal(err)
	}
	if vm["id"] != SlotUUID("abcDEF123", 1) || vm["add"] != "104.16.1.2" || vm["host"] != "relay.example.com" ||
		vm["path"] != "/vm" || vm["net"] != "ws" || vm["tls"] != "tls" || vm["v"] != "2" || vm["ps"] != "Relay · Germany" {
		t.Errorf("vmess: %v", vm)
	}

	// Shadowsocks only for the server itself: its other port follows
	// whatever this app last connected to.
	if links[0].SS == "" || links[1].SS != "" {
		t.Errorf("ss links: %q %q", links[0].SS, links[1].SS)
	}
	if !strings.HasPrefix(links[0].SS, "ss://") || !strings.Contains(links[0].SS, "@relay.example.com:8388#") {
		t.Errorf("ss link: %s", links[0].SS)
	}

	sub, _ := base64.StdEncoding.DecodeString(Subscription(links, false))
	if strings.Count(string(sub), "vless://") != 2 {
		t.Errorf("subscription: %s", sub)
	}
}

func TestExportLinksByAddress(t *testing.T) {
	links, err := ExportLinks(ExportServer{Host: "203.0.113.7", Edge: "104.16.1.2", ID: "x1"},
		[]ExportSlot{{2, "Relay · France"}})
	if err != nil {
		t.Fatal(err)
	}
	u, _ := url.Parse(links[0].VLESS)
	// No Cloudflare in front of an address, and no name to vouch for: the
	// address itself, and its own certificate accepted.
	if u.Host != "203.0.113.7:443" || u.Query().Get("allowInsecure") != "1" || u.Query().Get("sni") != "" {
		t.Errorf("vless by address: %s", links[0].VLESS)
	}
}

func TestExportNeedsAKeyThatHasIt(t *testing.T) {
	if _, err := ExportLinks(ExportServer{Host: "relay.example.com"}, []ExportSlot{{0, "x"}}); err == nil {
		t.Error("no export id should be refused")
	}
	if _, err := ExportLinks(ExportServer{Host: "h", ID: "i"}, []ExportSlot{{MaxExportSlots + 1, "x"}}); err == nil {
		t.Error("a slot past the most there can be should be refused")
	}
}

func TestKeyCarriesExport(t *testing.T) {
	key := func(body string) string {
		return KeyPrefix + base64.RawURLEncoding.EncodeToString([]byte(body))
	}
	k, err := ParseRelayKey(key(`{"v":1,"host":"203.0.113.7","pin":"","pw":"p","apw":"a","x":{"id":"abc_DEF-1","n":8}}`))
	if err != nil {
		t.Fatal(err)
	}
	if k.Export == nil || k.Export.ID != "abc_DEF-1" || k.Export.Slots != 8 {
		t.Errorf("export: %+v", k.Export)
	}
	for _, bad := range []string{`{"id":"","n":8}`, `{"id":"a b","n":8}`, `{"id":"a","n":0}`, `{"id":"a","n":99}`} {
		if _, err := ParseRelayKey(key(`{"v":1,"host":"203.0.113.7","pw":"p","apw":"a","x":` + bad + `}`)); err == nil {
			t.Errorf("x %s should be refused", bad)
		}
	}
	// A key from before exports is still a key.
	if k, err := ParseRelayKey(key(`{"v":1,"host":"203.0.113.7","pw":"p","apw":"a"}`)); err != nil || k.Export != nil {
		t.Errorf("old key: %+v, %v", k, err)
	}
}
