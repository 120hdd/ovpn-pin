package core

import "encoding/json"

// What a run says while it is going, in the shape window.onReach reads.
//
// The keys are engine.py's, name for name, because the handler drawing them
// is the same handler. Anything absent is absent rather than zero: the page
// reads `p.again` as a truth value to decide whether to say "asking those
// that did not answer once more", and an `again: 0` on every event would
// print that sentence through the whole run.
type reachEvent struct {
	Phase  string    `json:"phase"`
	Done   int       `json:"done"`
	Total  int       `json:"total"`
	Again  int       `json:"again,omitempty"`
	File   string    `json:"file,omitempty"`
	Result *ReachRec `json:"result,omitempty"`
}

func (e reachEvent) json() string {
	b, err := json.Marshal(e)
	if err != nil {
		return `{"phase":"testing","done":0,"total":0}`
	}
	return string(b)
}
