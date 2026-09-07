package relay

import "github.com/ERelay/ovpn-pin/mobile/core"

// Pinning, across the bridge.
//
// The desktop shells out to a PowerShell script for this. A phone has no
// PowerShell, and the two things that actually matter - a resolver that is
// itself pinned, and a list of reserved prefixes to throw away - are already
// in core. So this is the same run, in the same phases, saying the same
// things to the same handler.

// PinProgress is what the phone implements to hear how a run is going. Each
// call carries one JSON object in the shape window.onPin reads.
//
// Called from worker goroutines, which on Android are not the main thread.
type PinProgress interface {
	OnPin(payload string)
}

type pinSaid struct{ f func(string) }

func (p pinSaid) OnPin(payload string) { p.f(payload) }

// PinOptions is what a run is asked for. A struct because gomobile carries
// those, and because five loose parameters in a row is a signature that gets
// called with two of them the wrong way round exactly once and then behaves
// strangely forever.
type PinOptions struct {
	Inbox  string
	Out    string
	Route  string
	MaxIPs int
	Test   bool
}

// NewPinOptions is the defaults: the Surfshark inbox, into the folder the app
// races from, resolving directly, four addresses a config, and checking that
// each one answers before writing it.
//
// Directly rather than through the tunnel, because a phone with nothing
// pinned has no tunnel - and this is the run that gives it one.
func NewPinOptions() *PinOptions {
	return &PinOptions{Route: "direct", MaxIPs: 4, Test: true}
}

func (o *PinOptions) core() *core.PinOptions {
	return &core.PinOptions{
		Inbox: o.Inbox, Out: o.Out, Route: o.Route,
		MaxIPs: o.MaxIPs, Test: o.Test,
	}
}

// PinPlanJSON is what a run would cost and what would stop it.
func PinPlanJSON(o *PinOptions, quick bool) (out string) {
	defer said(&out)
	if o == nil {
		o = NewPinOptions()
	}
	return core.PinPlanJSON(o.core(), quick)
}

// StartPin begins a run and answers at once. Everything else arrives as
// events, because the page draws each result on the row it belongs to as it
// lands.
func StartPin(o *PinOptions, p PinProgress) (out string) {
	defer said(&out)
	if o == nil {
		o = NewPinOptions()
	}
	var progress core.PinProgress
	if p != nil {
		progress = pinSaid{p.OnPin}
	}
	return core.StartPin(o.core(), progress)
}

// CancelPin stops a run in flight.
func CancelPin() (out string) {
	defer said(&out)
	return core.CancelPin()
}
