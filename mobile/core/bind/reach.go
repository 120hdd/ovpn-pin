package relay

import (
	"encoding/json"
	"sync"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// The row-at-a-time reachability test, across the bridge.
//
// One row rather than the whole list, and that is the whole of the design.
// Asking all four hundred was a single button in the header and it was the
// wrong shape twice over: nobody wants to know about ninety countries, and a
// run that long is where the line starts dropping connections and the answers
// stop being about the exits. A country is twenty addresses and four seconds,
// and it is the country somebody was already looking at.

// ReachProgress is what the phone implements to hear how a run is going. Each
// call carries one JSON object in the shape window.onReach reads.
//
// Called from worker goroutines, which on Android are not the main thread: an
// implementation that touches the page has to post the work itself.
type ReachProgress interface {
	OnReach(payload string)
}

type reachSaid struct{ f func(string) }

func (r reachSaid) OnReach(p string) { r.f(p) }

// The channel a running test watches for a cancel. Held on the package rather
// than on the Client because the window can hold more than one Client over a
// run and there is only ever one test.
var (
	reachMu   sync.Mutex
	reachStop chan struct{}
)

// TestReach asks the exits behind one row whether they answer.
//
// Blocking, and meant to be: the phone calls it off its own thread and stops
// it with CancelReach. Returns the run in the shape onReachDone reads, minus
// the country list - which the caller splices in, because it has to reload
// the catalogue afterwards anyway and doing it twice would be two scans.
func (c *Client) TestReach(code string, timeoutMs, width int, p ReachProgress) (out string) {
	defer said(&out)

	c.mu.Lock()
	cat := c.cat
	logins := core.Logins{}
	for k, v := range c.logins {
		logins[k] = v
	}
	c.mu.Unlock()

	if cat == nil {
		return refused("no-servers")
	}
	pool := cat.Pool(code)
	if len(pool) == 0 {
		return refused("no-servers")
	}

	stop := make(chan struct{})
	reachMu.Lock()
	if reachStop != nil {
		reachMu.Unlock()
		return refused("busy")
	}
	reachStop = stop
	reachMu.Unlock()
	defer func() {
		reachMu.Lock()
		reachStop = nil
		reachMu.Unlock()
	}()

	var progress core.ReachProgress
	if p != nil {
		progress = reachSaid{p.OnReach}
	}

	tested, total, ok, refusedBy, cancelled, err := core.TestReach(
		pool, logins, core.LoadReach(), ms(timeoutMs), width, progress, stop)
	if err != nil {
		return refused(err.Error())
	}

	b, mErr := json.Marshal(struct {
		OK        bool `json:"ok"`
		Tested    int  `json:"tested"`
		Total     int  `json:"total"`
		Answered  int  `json:"answered"`
		Refused   int  `json:"refused"`
		Cancelled bool `json:"cancelled"`
	}{true, tested, total, ok, refusedBy, cancelled})
	if mErr != nil {
		return refused(mErr.Error())
	}
	return string(b)
}

// CancelReach stops a run in flight. Safe when none is going.
func (c *Client) CancelReach() {
	reachMu.Lock()
	defer reachMu.Unlock()
	if reachStop != nil {
		close(reachStop)
		reachStop = nil
	}
}

// refused is a run that did not start, in the shape onReachDone reads. The
// page prints `error`, and the three it knows how to reword - no-servers,
// no-credentials, busy - go across unchanged.
func refused(why string) string {
	b, err := json.Marshal(struct {
		OK    bool   `json:"ok"`
		Error string `json:"error"`
	}{false, why})
	if err != nil {
		return `{"ok":false,"error":"could not start"}`
	}
	return string(b)
}
