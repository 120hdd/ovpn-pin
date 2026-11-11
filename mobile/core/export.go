package core

// Configs for other devices: an iPhone, a friend's laptop - anything with a
// V2Ray client and no Relay - reaching the user's own server, and through it
// a provider location picked here.
//
// The server holds a handful of numbered locations ("slots"). Each is a
// chain on its gost, loc-<k>, that this app points at a provider exit the
// same way server + exit is pointed - the exit in that location that
// uploads best from the server - and a user on its sing-box, slot-<k>,
// routed to that chain. Slot 0 is the server itself. A link is one slot as a
// V2Ray client reads it: VLESS or VMess over WebSocket and TLS, through the
// same nginx and the same Cloudflare the apps use.
//
// The users' UUIDs are not in the key. They are derived from one id that is
// (x.id), the same way the installer derived them, so the key stays short
// enough to paste and the apps can make any slot's link without asking.

import (
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// RelayKeyExport is what a key says about the server's exported locations.
type RelayKeyExport struct {
	ID    string `json:"id"`
	Slots int    `json:"slots"`
}

// The WebSocket paths nginx sends to sing-box. Fixed by the installer.
const (
	ExportVLESSPath = "/vl"
	ExportVMessPath = "/vm"
)

// MaxExportSlots is the most locations an installer will set up.
const MaxExportSlots = 32

// namespaceURL is RFC 4122's URL namespace, which the installer's Python
// uses too: uuid.uuid5(uuid.NAMESPACE_URL, ...).
var namespaceURL = [16]byte{0x6b, 0xa7, 0xb8, 0x11, 0x9d, 0xad, 0x11, 0xd1,
	0x80, 0xb4, 0x00, 0xc0, 0x4f, 0xd4, 0x30, 0xc8}

// SlotUUID is slot k's user on the server.
func SlotUUID(id string, k int) string {
	h := sha1.New()
	h.Write(namespaceURL[:])
	h.Write([]byte("relay-export:" + id + ":" + strconv.Itoa(k)))
	u := h.Sum(nil)[:16]
	u[6] = u[6]&0x0f | 0x50
	u[8] = u[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", u[0:4], u[4:6], u[6:8], u[8:10], u[10:16])
}

// ExportSlot is one slot to make links for, and what to call it.
type ExportSlot struct {
	K    int    `json:"k"`
	Name string `json:"name"`
}

// ExportLink is one slot's links.
type ExportLink struct {
	K     int    `json:"k"`
	Name  string `json:"name"`
	VLESS string `json:"vless"`
	VMess string `json:"vmess"`
	// Only for the server itself, and only when the key carries
	// Shadowsocks: its other port exits wherever this app last connected,
	// which is no use to somebody else's phone.
	SS string `json:"ss,omitempty"`
}

// ExportServer is how other devices reach the server.
type ExportServer struct {
	Host string // the server's address or name, as the key has it
	Port int    // its TLS port; 0 means 443, which is what links carried before
	// Edge is a Cloudflare address measured to carry the name from here,
	// put in the link's address so the device does not have to look the name
	// up on a line that lies about it. Empty to use the name.
	Edge string
	ID   string // the key's x.id
	SS   *RelayKeySS
}

// ExportLinks makes every slot's links.
func ExportLinks(srv ExportServer, slots []ExportSlot) ([]ExportLink, error) {
	if srv.Host == "" || srv.ID == "" {
		return nil, errors.New("this server's key has no exported locations - run the installer from this version again and paste its new key")
	}
	byIP := net.ParseIP(srv.Host) != nil
	addr := srv.Host
	if !byIP && srv.Edge != "" && net.ParseIP(srv.Edge) != nil {
		addr = srv.Edge
	}
	out := make([]ExportLink, 0, len(slots))
	for _, s := range slots {
		if s.K < 0 || s.K > MaxExportSlots {
			return nil, fmt.Errorf("slot %d is out of range", s.K)
		}
		id := SlotUUID(srv.ID, s.K)
		l := ExportLink{K: s.K, Name: s.Name,
			VLESS: vlessLink(id, addr, srv.Port, srv.Host, byIP, s.Name),
			VMess: vmessLink(id, addr, srv.Port, srv.Host, byIP, s.Name)}
		if s.K == 0 && srv.SS != nil {
			l.SS = ssLink(srv.Host, srv.SS, s.Name)
		}
		out = append(out, l)
	}
	return out, nil
}

// vlessLink is the share link most clients read: Shadowrocket, Streisand,
// V2Box, Hiddify, v2rayNG, NekoBox, sing-box. A server by address has a
// certificate of its own that no device will trust by itself, so the link
// says to accept it - which is what the apps' pinning replaces, and these
// clients have no way to pin.
func vlessLink(id, addr string, port int, host string, byIP bool, name string) string {
	q := url.Values{}
	q.Set("encryption", "none")
	q.Set("security", "tls")
	q.Set("type", "ws")
	q.Set("path", ExportVLESSPath)
	if byIP {
		q.Set("allowInsecure", "1")
	} else {
		q.Set("sni", host)
		q.Set("host", host)
	}
	q.Set("fp", "chrome")
	return "vless://" + id + "@" + net.JoinHostPort(addr, strconv.Itoa(tlsPort(port))) + "?" + q.Encode() +
		"#" + url.PathEscape(name)
}

// vmessLink is v2rayN's format: base64 of a JSON object, version 2.
func vmessLink(id, addr string, port int, host string, byIP bool, name string) string {
	m := map[string]string{
		"v": "2", "ps": name, "add": addr, "port": strconv.Itoa(tlsPort(port)), "id": id, "aid": "0",
		"scy": "auto", "net": "ws", "type": "none", "path": ExportVMessPath,
		"tls": "tls", "fp": "chrome",
	}
	if byIP {
		m["allowInsecure"] = "1"
	} else {
		m["host"] = host
		m["sni"] = host
	}
	b, _ := json.Marshal(m)
	return "vmess://" + base64.StdEncoding.EncodeToString(b)
}

// ssLink is SIP002: the method and password base64url'd, unpadded.
func ssLink(host string, ss *RelayKeySS, name string) string {
	user := base64.RawURLEncoding.EncodeToString([]byte(ss.Method + ":" + ss.Password))
	return "ss://" + user + "@" + net.JoinHostPort(host, strconv.Itoa(ss.Port)) + "#" + url.PathEscape(name)
}

// Subscription is every link, one a line, base64'd - what a client asks a
// subscription URL for, and what most of them take pasted as well.
func Subscription(links []ExportLink, vmess bool) string {
	var lines []string
	for _, l := range links {
		if vmess {
			lines = append(lines, l.VMess)
		} else {
			lines = append(lines, l.VLESS)
		}
	}
	return base64.StdEncoding.EncodeToString([]byte(strings.Join(lines, "\n")))
}

func validExportID(id string) bool {
	if id == "" || len(id) > 64 {
		return false
	}
	for _, r := range id {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r == '-' || r == '_') {
			return false
		}
	}
	return true
}
