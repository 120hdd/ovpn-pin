package core

import (
	"errors"
	"sync/atomic"
	"time"
)

// health is how the way out has answered the connections asked of it.
//
// It exists because, without it, nothing on the phone could tell a tunnel
// that carries from one that does not. The stack came up, the interface was
// routed, and the status said "connected" - and when the exit stopped
// answering half an hour later (filtered, overloaded, gone), every new
// connection waited out its fifteen seconds and failed, every DNS query
// waited out its ten, and the status still said "connected", because the only
// thing that had ever been checked was that the tunnel started.
//
// Counted, not measured: no traffic is sent for it. A failure is the way's own
// - the TCP that did not complete, the TLS nobody answered, the CONNECT that
// never came back - and a refusal is not one, because an exit that answers
// 502 for a dead site is an exit that is working.
//
// In its own file rather than in tunnel.go because tunnel.go is built only
// for the phones, and this is worth testing on the machine the tests run on.
type health struct {
	fails  atomic.Int32 // in a row, reset by the first that works
	lastOK atomic.Int64 // unix milliseconds, of the last answer the way gave
}

func (h *health) note(err error) {
	if err == nil || errors.Is(err, ErrTargetRefused) {
		h.fails.Store(0)
		h.lastOK.Store(time.Now().UnixMilli())
		return
	}
	h.fails.Add(1)
}

func (h *health) reset() {
	h.fails.Store(0)
	h.lastOK.Store(time.Now().UnixMilli())
}

func (h *health) read() (fails int, sinceOK time.Duration) {
	return int(h.fails.Load()), time.Since(time.UnixMilli(h.lastOK.Load()))
}
