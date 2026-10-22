package core

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"
)

// One credential per provider, because two providers on one phone is the
// ordinary case and they are not one account.
//
// Everything above this used to take a user and a password, which is right
// for a folder holding one provider's exits and quietly wrong for a folder
// holding both: a Windscribe exit asked with a Surfshark credential is
// refused, and a refusal reads as "this exit is blocked here" rather than as
// "you asked it the wrong question".

// Login is what one provider's proxy wants.
type Login struct{ User, Password string }

// Logins is those, by provider name - the same two names the filenames carry,
// "surfshark" and "windscribe".
type Logins map[string]Login

// For is the credential to open this exit with.
//
// An exit whose provider is not known - one added by address rather than read
// out of a folder - is Surfshark's, because that is the only way an exit gets
// into this app without a filename to say otherwise.
func (l Logins) For(s Server) (Login, bool) {
	name := s.Provider
	if name == "" {
		name = "surfshark"
	}
	login, ok := l[name]
	return login, ok && login.User != "" && login.Password != ""
}

// Keep is the exits in a pool that something can actually open, and it is
// where a missing credential becomes an answer instead of eighty timeouts.
//
// Half a pool is still a pool: a phone signed into one provider and not the
// other should connect through the one it has rather than refuse. Only when
// nothing is left does the missing credential become the thing that stopped
// the connect, and then it says which provider it wanted.
func (l Logins) Keep(pool []Server) ([]Server, error) {
	kept := make([]Server, 0, len(pool))
	missing := map[string]bool{}
	for _, s := range pool {
		if _, ok := l.For(s); ok {
			kept = append(kept, s)
			continue
		}
		name := s.Provider
		if name == "" {
			name = "surfshark"
		}
		missing[name] = true
	}
	if len(kept) > 0 {
		return kept, nil
	}
	if len(missing) == 0 {
		return nil, ErrNoServers
	}
	names := make([]string, 0, len(missing))
	for name := range missing {
		names = append(names, name)
	}
	sort.Strings(names)
	return nil, fmt.Errorf("no credentials for %s", names[0])
}

// ErrNoCredentials is nothing signed in at all, which the window has its own
// screen for.
var ErrNoCredentials = errors.New("no-credentials")

// AskExit is the one question worth asking of an exit: does it take these
// credentials, and how long did it take to say so.
//
// Two providers, two questions, and the difference is not a detail.
// Surfshark's proxy refuses an unauthenticated CONNECT with 407, so a 200
// proves the credential and stopping there is honest. Windscribe's nghttpx
// answers 200 to anybody and then forwards nothing - so the same check would
// report every exit alive and hand back one that silently drops everything.
// Its exits are asked with a whole request instead, tunnelled through and
// read back.
//
// Twice the budget for that, capped at eight seconds, because it is doing
// about twice the work: a CONNECT, an inner handshake and a page. A cold dial
// measured up to 4.7s on this line, which is inside six seconds only just -
// and an exit failed for being slow is one the race never comes back to.
func AskExit(s Server, l Login, timeout time.Duration) (time.Duration, error) {
	e := NewExit(s.Addr, s.Name, l.User, l.Password)
	if s.Provider != "windscribe" {
		return Ask(e, timeout)
	}
	return Verify(e, timeout)
}

// Verify is "does this actually carry traffic", asked of any way out.
//
// windscribe.py's verify_tunnel: open the tunnel, fetch a page through it,
// and require an address to come back. Anything less is answered by a proxy
// that forwards nothing.
func Verify(w Way, timeout time.Duration) (time.Duration, error) {
	budget := 2 * timeout
	if budget > 8*time.Second {
		budget = 8 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()

	started := time.Now()
	if _, _, err := SeenAs(ctx, w, budget); err != nil {
		return 0, err
	}
	return time.Since(started), nil
}
