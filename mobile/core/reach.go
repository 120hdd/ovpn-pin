package core

import (
	"encoding/json"
	"os"
)

// What the last test found, by config filename.
//
// The finding is worth more than the run, which is why it is on disk at all:
// a country whose every address was refused an hour ago is worth showing as
// blocked now rather than making somebody discover it again. The file is the
// desktop's .state/reach.json, key for key, so a folder carried between the
// two does not lose what was measured about it.

// ReachRec is one exit's verdict.
//
// The pointers are the point. `ok` has three states and they are three
// different sentences: true is "this took the credentials", false is "this
// was refused or never answered", and null is "it answered at the address
// and the second question has not been asked yet". A bool could not say the
// third, and the desktop writes it on every ping.
type ReachRec struct {
	OK     *bool    `json:"ok"`
	Ms     *int     `json:"ms"`
	IP     string   `json:"ip,omitempty"`
	Why    string   `json:"why,omitempty"`
	At     int64    `json:"at"`
	Opened *float64 `json:"opened,omitempty"`
}

// Answered is whether this exit is known to work - `ok` true and nothing
// else. Written out because `rec.OK != nil && *rec.OK` reads like a null
// check rather than like a question about an exit.
func (r ReachRec) Answered() bool { return r.OK != nil && *r.OK }

// LoadReach reads the store. Unreadable is empty, deliberately: the app has
// to work having never tested anything, and that is the same state.
func LoadReach() map[string]ReachRec {
	out := map[string]ReachRec{}
	b, err := os.ReadFile(ReachPath())
	if err != nil {
		return out
	}
	if err := json.Unmarshal(b, &out); err != nil {
		return map[string]ReachRec{}
	}
	return out
}

// SaveReach writes it through a temporary file in the same directory.
func SaveReach(found map[string]ReachRec) error {
	return writeJSON(ReachPath(), found)
}

// REFUSED is the sentence an exit gives when the account is not the problem
// with the line but is the problem with the exit. Counted separately by a
// run, because "0 of 37 answered" is true both of a line filtering every
// address and of an account every address turns away, and only one of those
// is a fact about the exits.
//
// The same string exit.go produces on a 407, and the same string engine.py
// looks for. Three places, one sentence, and it has to stay that way.
const REFUSED = "no proxy for this account"
