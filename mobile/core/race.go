package core

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// RaceWidth is how many exits are asked at once.
//
// Eight rather than something impressive. Measured on the line this repo was
// written on, racing wider makes it slower, because the handshakes compete
// for the same upstream:
//
//	width  6   0.86s  1.02s  1.14s
//	width 10   1.11s  1.16s  1.02s
//	width 16   1.39s  1.38s  1.36s
//	width 24   1.89s  2.25s  2.14s
//
// Eight also stages itself: the pool keeps feeding candidates in, so a bad
// moment when only one exit in twenty is accepting still works through the
// list, just over a few more rounds.
const RaceWidth = 8

// Progress is what the window is told while it waits. Called from whichever
// goroutine finished a probe, so an implementation that touches UI has to
// hand the work on itself.
type Progress func(asked, total int)

// Winner is the exit that answered first, and how long it took to say so.
type Winner struct {
	Server Server
	Took   time.Duration
}

var (
	// ErrNoServers is nothing to race, which is a different thing from
	// racing and having them all refuse - one is a missing folder or a
	// filter that matched nothing, the other is the line or the account.
	ErrNoServers  = errors.New("no-servers")
	ErrAllRefused = errors.New("all-refused")
)

// Race asks the candidates and takes the first that says yes.
//
// First rather than best. An earlier version of the desktop waited for two so
// it could pick the quicker, and paid the difference between them on every
// connect - which is not worth a second of somebody's time.
//
// The context is the cancel: a caller that gives up releases Race
// immediately, rather than at the timeout of the slowest probe still in
// flight. The probes it leaves behind are goroutines holding one socket each
// and they end on their own deadline, which is what the desktop's
// shutdown(wait=False) amounts to.
func Race(ctx context.Context, servers []Server, logins Logins,
	timeout time.Duration, width int, progress Progress) (Winner, error) {

	if len(servers) == 0 {
		return Winner{}, ErrNoServers
	}
	// Before the race rather than inside it, so "you are not signed in to
	// that" is an answer that arrives at once instead of after eighty
	// timeouts - and so a folder holding both providers still races the half
	// there is a credential for.
	servers, err := logins.Keep(servers)
	if err != nil {
		return Winner{}, err
	}
	if width <= 0 {
		width = RaceWidth
	}

	ctx, stop := context.WithCancel(ctx)
	defer stop()

	total := len(servers)
	var done int64
	if progress != nil {
		progress(0, total)
	}

	// Buffered by the width, so the goroutines that lose the race can post
	// their result and exit rather than blocking forever on a channel nobody
	// will read again.
	found := make(chan Winner, width)
	feed := make(chan Server)

	var wg sync.WaitGroup
	for i := 0; i < width; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for s := range feed {
				if ctx.Err() != nil {
					return
				}
				login, ok := logins.For(s)
				if !ok {
					continue
				}
				took, err := AskExit(s, login, timeout)

				n := atomic.AddInt64(&done, 1)
				// Every fourth, and the last. One call per probe would be
				// eight redraws a second for the whole run.
				if progress != nil && (n%4 == 0 || int(n) == total) {
					progress(int(n), total)
				}
				if err != nil {
					continue
				}
				select {
				case found <- Winner{Server: s, Took: took}:
				default:
				}
				return
			}
		}()
	}

	go func() {
		defer close(feed)
		for _, s := range servers {
			select {
			case feed <- s:
			case <-ctx.Done():
				return
			}
		}
	}()

	// Closed once every worker has stopped, which is how "they all refused"
	// is told apart from "still going".
	exhausted := make(chan struct{})
	go func() {
		wg.Wait()
		close(exhausted)
	}()

	select {
	case w := <-found:
		return w, nil
	case <-exhausted:
		// One last look: a winner posted in the same moment the last worker
		// finished would otherwise be dropped on the floor.
		select {
		case w := <-found:
			return w, nil
		default:
			return Winner{}, ErrAllRefused
		}
	case <-ctx.Done():
		return Winner{}, ctx.Err()
	}
}
