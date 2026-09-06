package relay

import (
	"context"
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
	Domain   string
	Password string // the CONNECT credential
	Mode     string // "single", "multi" or "bulk"

	// Edges is the addresses to dial, newest measurement first, one per line.
	// Empty means dial the domain by name - which works on a line that is not
	// lying about this particular name, and is what the first run does before
	// anything has been measured.
	Edges string
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
func ScanEdges(domain string, timeoutMs int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(),
		time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	live, said, err := core.EdgesFor(ctx, domain, 2500*time.Millisecond)
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

	srv := core.NewTunnelServer(cfg.Domain, cfg.Password, cfg.edges(),
		core.TunnelMode(cfg.Mode))
	defer srv.Close()

	return core.SeenAsThrough(srv, timeout)
}
