package core

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Asking every exit whether it answers, and keeping what it said.
//
// This is the thing the app could never say. An exit filtered on this line
// looks exactly like one that is merely slow, and the only way to tell them
// apart is to ask - so the answer was always "try connecting and see", which
// spends the same time and throws the finding away.
//
// engine.py's test_reach, ported rather than reinvented: same two passes,
// same second look, same record shape on disk, so a phone and a desktop
// reading the same folder agree about what was measured.

// ReachProgress is how a run says where it has got to.
//
// One JSON string rather than a shape, for the reason everything else here
// crosses as JSON: gomobile carries no maps, and the page parses the payload
// either way. Called from whichever goroutine finished a probe, so an
// implementation that touches a view has to post the work itself.
type ReachProgress interface {
	OnReach(json string)
}

// A run at a time. The page disables its own button, but a second run started
// some other way would have two sets of workers writing one file.
var reachRunning atomic.Bool

// ErrReachBusy is a second run asked for while one is going.
var ErrReachBusy = errors.New("busy")

// TestReach asks a pool of exits whether they answer and whether they take
// the credentials, and writes down what they said.
//
// Two passes, because the two questions cost different amounts.
//
// A ping is one round trip and comes back in a tenth of a second. The verdict
// is a TLS handshake, a certificate checked by hand, a CONNECT, and for
// Windscribe a request tunnelled through and read back; on a blocked address
// it is a timeout, and the timeouts are what a run waits for. Asked together,
// every ping arrived at the speed of the slowest thing beside it and the list
// sat empty for a minute before filling in at once. Asked apart, the times
// land in the first few seconds and the verdicts follow.
//
// Blocking. The caller runs it off its own thread and stops it with Cancel.
func TestReach(
	pool []Server, logins Logins, reach map[string]ReachRec,
	timeout time.Duration, width int, p ReachProgress, cancel <-chan struct{},
) (tested, total, ok, refused int, cancelled bool, err error) {

	if len(pool) == 0 {
		return 0, 0, 0, 0, false, ErrNoServers
	}
	pool, err = logins.Keep(pool)
	if err != nil {
		return 0, 0, 0, 0, false, err
	}
	if width <= 0 {
		width = 12
	}
	if reach == nil {
		reach = map[string]ReachRec{}
	}

	if !reachRunning.CompareAndSwap(false, true) {
		return 0, 0, 0, 0, false, ErrReachBusy
	}
	defer reachRunning.Store(false)

	var mu sync.Mutex
	stopped := func() bool {
		select {
		case <-cancel:
			return true
		default:
			return false
		}
	}
	say := func(json string) {
		if p != nil {
			p.OnReach(json)
		}
	}
	write := func(file string, rec ReachRec) {
		mu.Lock()
		reach[file] = rec
		mu.Unlock()
	}

	total = len(pool)
	say(reachEvent{Phase: "testing", Total: total}.json())

	// -- does anything answer at the address at all ---------------------------

	answered, missed := ping(pool, reach, timeout, max(width, 24), say, stopped, write, total, 0)
	SaveReach(copyReach(&mu, reach))

	// A second look at the ones that did not, quietly and narrowly.
	//
	// Measured, and the reason this exists. A run over 389 addresses left 94
	// recorded as "timed out" - and every one of those, probed on its own a
	// minute later, answered in about 90ms. The same 389 at the same width,
	// run again, lost two. So what the first run wrote down was not a
	// property of those addresses: it was a moment on the line, and this line
	// is one where a few hundred new connections in a burst get some of them
	// dropped.
	//
	// That matters because the answer is kept. "No answer" takes an exit out
	// of the ordering and shows it as blocked until somebody thinks to test
	// again, so one bad thirty seconds cost a provider its whole fleet.
	//
	// Six at a time rather than twenty-four, because volume is what this is
	// about. Once only: an address that will not answer twice is being kept
	// from us, and a third ask is a slower run rather than a truer one.
	if len(missed) > 0 && !stopped() {
		say(reachEvent{Phase: "pinging", Done: total, Total: total,
			Again: len(missed)}.json())
		back, _ := ping(missed, reach, timeout, 6, say, stopped, write, total, total)
		answered = append(answered, back...)
		SaveReach(copyReach(&mu, reach))
	}

	// -- and then whether they take the credentials ---------------------------

	var done int64
	gate := make(chan struct{}, width)
	var wg sync.WaitGroup
	for _, s := range answered {
		if stopped() {
			break
		}
		wg.Add(1)
		go func(s Server) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			if stopped() {
				return
			}

			mu.Lock()
			rec := reach[s.File]
			mu.Unlock()

			login, have := logins.For(s)
			if !have {
				return
			}
			took, err := AskExit(s, login, timeout)
			yes := err == nil
			rec.OK = &yes
			if yes {
				opened := took.Seconds()
				rec.Opened = &opened
				rec.Why = ""
			} else {
				rec.Why = why(err)
			}
			rec.At = time.Now().Unix()
			write(s.File, rec)

			n := atomic.AddInt64(&done, 1)
			say(reachEvent{Phase: "testing", Done: int(n), Total: len(answered),
				File: s.File, Result: &rec}.json())
		}(s)
	}
	wg.Wait()
	SaveReach(copyReach(&mu, reach))

	// Counted rather than left in the per-exit rows. "0 of 37 answered" is
	// true of a line filtering every address and of an account every address
	// turns away, and only one of those is about the exits - so a run says
	// which it found.
	mu.Lock()
	for _, s := range pool {
		rec := reach[s.File]
		if rec.Answered() {
			ok++
		}
		if strings.Contains(rec.Why, REFUSED) {
			refused++
		}
	}
	mu.Unlock()

	return int(done), total, ok, refused, stopped(), nil
}

// ping is one pass of "does the address answer", which is a plain TCP
// connection and nothing else.
//
// This is the number that belongs beside a location, and it was not the
// number being shown before: what was shown was a whole handshake, a
// certificate verified by hand and a CONNECT, which on this line came to
// 1.2-2.6 seconds - a true measurement of something nobody asked about. A
// single round trip to Europe from here is nearer 60ms.
func ping(
	pool []Server, reach map[string]ReachRec, timeout time.Duration, width int,
	say func(string), stopped func() bool, write func(string, ReachRec),
	total, from int,
) (answered, missed []Server) {

	var mu sync.Mutex
	var done int64
	gate := make(chan struct{}, width)
	var wg sync.WaitGroup

	for _, s := range pool {
		if stopped() {
			break
		}
		wg.Add(1)
		go func(s Server) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			if stopped() {
				return
			}

			began := time.Now()
			conn, err := (&net.Dialer{Timeout: timeout}).DialContext(
				context.Background(), "tcp", net.JoinHostPort(s.Addr, "443"))
			var rec ReachRec
			if err == nil {
				took := int(time.Since(began).Milliseconds())
				conn.Close()
				rec = ReachRec{Ms: &took, IP: s.Addr, At: time.Now().Unix()}
				mu.Lock()
				answered = append(answered, s)
				mu.Unlock()
			} else {
				// No answer at the address at all: there is nothing to ask a
				// second question of, so it does not go through the slow pass
				// and does not wait out a TLS timeout that was never going to
				// complete.
				no := false
				rec = ReachRec{OK: &no, Why: why(err), At: time.Now().Unix()}
				if !stopped() {
					mu.Lock()
					missed = append(missed, s)
					mu.Unlock()
				}
			}
			write(s.File, rec)

			n := int(atomic.AddInt64(&done, 1))
			at := n
			if from > 0 {
				at = from
			}
			say(reachEvent{Phase: "pinging", Done: at, Total: total,
				File: s.File, Result: &rec}.json())
		}(s)
	}
	wg.Wait()
	return answered, missed
}

// why is the reason, short enough to sit in a row.
func why(err error) string {
	if err == nil {
		return ""
	}
	text := strings.TrimSpace(err.Error())
	if text == "" {
		return "no answer"
	}
	r := []rune(text)
	if len(r) > 60 {
		return string(r[:60])
	}
	return text
}

func copyReach(mu *sync.Mutex, reach map[string]ReachRec) map[string]ReachRec {
	mu.Lock()
	defer mu.Unlock()
	out := make(map[string]ReachRec, len(reach))
	for k, v := range reach {
		out[k] = v
	}
	return out
}
