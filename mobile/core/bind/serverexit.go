package relay

import (
	"errors"
	"strings"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// ExitFor is the provider exit server + exit leaves by, for a pick: a
// country, a city, one server, or "auto". The first of the pick's pool, as
// the desktop's address_for has it - nothing is asked from the phone, because
// whether an exit answers this line says nothing about whether it answers
// the server, which is the one that dials it.
//
// Load has to have run first; the pool comes from the catalogue it builds.
func (c *Client) ExitFor(code string) (*Result, error) {
	pool, err := c.exitPool(code)
	if err != nil {
		return nil, err
	}
	s := pool[0]
	return &Result{Addr: s.Addr, Name: s.Name, Path: s.Path, File: s.File, Provider: s.Provider}, nil
}

func (c *Client) exitPool(code string) ([]core.Server, error) {
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return nil, errors.New("no servers loaded")
	}
	if code == "auto" {
		code = ""
	}
	pool := cat.Pool(code)
	if len(pool) == 0 {
		return nil, errors.New("no exit in " + code)
	}
	return pool, nil
}

// Upload worth keeping, in bytes a second. A throttled exit carries 10-20
// KB/s up from an Iranian server; a free one carries whatever the phone's own
// line does, which is hundreds.
const goodUpload = 64 << 10

// BestServerExit points the server at the pick's exit that uploads, and says
// which it was.
//
// From a server in Iran, upload is throttled per foreign exit IP, so the
// first exit of a city is as likely as not to carry nothing up. Each is set
// on the server in turn and asked to carry a 256 KB upload end to end - the
// phone's line, the server, the exit - and the first that does it at a usable
// rate is kept. None good enough: the fastest of those tried is kept, so a
// connection is still made and the window can say why it is slow.
//
// A pick of one server is that server, measured but not replaced: it was
// chosen by hand.
//
// The Result's TookMs is the measured upload, in KB/s.
func (c *Client) BestServerExit(cfg *ServerConfig, apiPassword, code, ssUser, ssPassword,
	wsUser, wsPassword string, tries, timeoutMs int) (*Result, error) {

	if cfg == nil || cfg.Domain == "" {
		return nil, errors.New("no server configured")
	}
	pool, err := c.exitPool(code)
	if err != nil {
		return nil, err
	}
	if strings.HasPrefix(code, "file:") || tries < 1 {
		tries = 1
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	type tried struct {
		s    core.Server
		user string
		pass string
		rate float64
	}
	var best *tried
	var last error
	for _, s := range pool {
		if tries == 0 {
			break
		}
		user, pass := ssUser, ssPassword
		if s.Provider == "windscribe" {
			user, pass = wsUser, wsPassword
		}
		if user == "" {
			continue
		}
		tries--
		if err := core.SetExit(cfg.Domain, apiPassword, cfg.Pin, s.Addr, user, pass, s.File, 15*time.Second); err != nil {
			return nil, err // the server itself refused: no other exit will fare better
		}
		multi := *cfg
		multi.Mode = "multi"
		srv := multi.server()
		rate, err := core.UploadRate(srv, 256<<10, timeout)
		srv.Close()
		if err != nil {
			last = err
			rate = 0
		}
		if best == nil || rate > best.rate {
			best = &tried{s, user, pass, rate}
		}
		if rate >= goodUpload {
			break
		}
	}
	if best == nil {
		if last == nil {
			last = errors.New("no account for any exit in " + code)
		}
		return nil, last
	}
	// The best was not the last one set: put it back.
	if err := core.SetExit(cfg.Domain, apiPassword, cfg.Pin, best.s.Addr, best.user, best.pass,
		best.s.File, 15*time.Second); err != nil {
		return nil, err
	}
	s := best.s
	return &Result{Addr: s.Addr, Name: s.Name, Path: s.Path, File: s.File, Provider: s.Provider,
		TookMs: int(best.rate / 1024)}, nil
}
