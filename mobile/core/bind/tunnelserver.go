package relay

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// The user's own server, as the phone sees it.
//
// The desktop reaches this by running gost as a child process and pointing
// its proxy at the local port that comes up. A phone does not spawn processes
// comfortably and does not have to: gost is Go, this core is Go, and the mwss
// path is a websocket, a smux session and a CONNECT.
//
// Everything here is flat for the same reason the rest of this package is:
// gomobile carries strings, ints and errors, so the edges arrive as one
// newline-separated string rather than as a slice.

// ServerConfig is what the phone needs to know to reach the server.
//
// A struct because gomobile does carry those, and because three loose string
// parameters in that order is the kind of signature that gets called with the
// passwords the wrong way round exactly once and then behaves strangely
// forever.
type ServerConfig struct {
	Domain string
	// Port is where the server takes TLS; 0 means 443. Only the installer's
	// --port makes it anything else, and the key then says so.
	Port     int
	Password string // the CONNECT credential
	Mode     string // "single", "multi" or "bulk"

	// Edges is the addresses to dial, newest measurement first, one per line.
	// Empty means dial the domain by name - which works on a line that is not
	// lying about this particular name, and is what the first run does before
	// anything has been measured.
	Edges string

	// Pin is the SHA-256 of the certificate a server reached by IP serves,
	// in hex. Empty until the first Test or connect has seen it; then kept,
	// and a server showing anything else is refused.
	Pin string

	// Transport is how the server is reached: "" or "ws" for the WebSocket
	// (through Cloudflare, or straight to the address), "ss" for Shadowsocks
	// straight to the address. The SS fields come from the server's key.
	Transport  string
	SSMethod   string
	SSPassword string
	SSPort     int // exits at the server: single and bulk
	SSExitPort int // exits at the chosen provider location: multi
}

func NewServerConfig() *ServerConfig { return &ServerConfig{Mode: "single"} }

func (c *ServerConfig) edges() []string {
	var out []string
	for _, line := range strings.Split(c.Edges, "\n") {
		if s := strings.TrimSpace(line); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// ScanEdges measures which Cloudflare addresses will carry the domain from
// this line, and returns them one per line, quickest first.
//
// The same question the desktop asks before it writes a gost config, and it
// has to be asked from the phone rather than copied from the desktop: which
// addresses are filtered is a fact about the line the phone is on, and the
// two are not always the same line.
//
// Blocking, and about five seconds - fifty-six addresses at twenty-eight at a
// time, and the blocked ones are what it waits for.
func ScanEdges(domain string, port, timeoutMs int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(),
		time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	live, said, err := core.EdgesFor(ctx, domain, port, 2500*time.Millisecond)
	if err != nil {
		// The tally comes back with the error rather than instead of it:
		// "no edges" has four causes and only one of them is the server.
		return said, err
	}
	return strings.Join(live, "\n"), nil
}

// TestServer opens the tunnel, asks one question through it and reports where
// it came out. What a "test this server" button calls.
//
// Returns the same JSON shape as SeenAsJSON, so the window can show the
// answer with the code it already has.
func TestServer(cfg *ServerConfig, timeoutMs int) (string, error) {
	if cfg == nil || cfg.Domain == "" {
		return "", errors.New("no server to test")
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	srv := cfg.server()
	defer srv.Close()

	out, err := core.SeenAsThrough(srv, timeout)
	return withPin(out, srv.SeenPin()), err
}

// server is the client this config describes, pin and all.
//
// Over Shadowsocks the mode is a port rather than a path: multi dials the
// port that goes on through the server's exit-chain, the other two the one
// that exits at the server. Bulk has nothing to add there - Shadowsocks is
// one connection per connection already.
func (c *ServerConfig) server() core.ServerWay {
	if c.Transport == "ss" {
		port := c.SSPort
		if core.TunnelMode(c.Mode) == core.ModeMulti {
			port = c.SSExitPort
		}
		return core.NewShadowsocksServer(c.Domain, port, c.SSMethod, c.SSPassword)
	}
	srv := core.NewTunnelServer(c.Domain, c.Password, c.edges(), core.TunnelMode(c.Mode))
	srv.Pin = c.Pin
	srv.Port = c.Port
	return srv
}

// ParseKey reads the key the server's installer printed (relay://...) and
// hands back what it says as JSON, checked: host, pin, password,
// apiPassword, port (the server's TLS port, 443 when the key has none) and,
// when the server offers it, ss {method, password, port, exitPort}. What the settings screen calls when a key is pasted.
func ParseKey(text string) (string, error) {
	k, err := core.ParseRelayKey(text)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(k)
	return string(out), err
}

// withPin adds the certificate the server showed to a seen-as answer, so the
// window can keep it after the first Test - the pin is learnt, not typed.
func withPin(seenAs, pin string) string {
	if pin == "" {
		return seenAs
	}
	var m map[string]any
	if json.Unmarshal([]byte(seenAs), &m) != nil || m == nil {
		m = map[string]any{}
	}
	m["pin"] = pin
	out, _ := json.Marshal(m)
	return string(out)
}

// SetServerExit points the server's multi-IP mode at one provider exit,
// with that exit's own provider credential - what server + exit does before
// it connects, so the location picked is the one traffic leaves by.
func SetServerExit(cfg *ServerConfig, apiPassword, exitIP, user, password, name string, timeoutMs int) error {
	if cfg == nil || cfg.Domain == "" {
		return errors.New("no server configured")
	}
	return core.SetExit(cfg.Domain, cfg.Port, apiPassword, cfg.Pin, exitIP, user, password, name,
		time.Duration(timeoutMs)*time.Millisecond)
}

// SeenAsLiveJSON is where the running tunnel comes out, asked through
// whatever is carrying it. What the window asks for the user's own server:
// SeenAsJSON rebuilds a provider exit from an address and a credential, and a
// server has neither of those to give it.
func SeenAsLiveJSON(timeoutMs int) string {
	w := core.LiveWay()
	if w == nil {
		return "{}"
	}
	out, _ := core.SeenAsThrough(w, time.Duration(timeoutMs)*time.Millisecond)
	return out
}
