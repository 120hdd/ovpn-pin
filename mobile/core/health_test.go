package core

import (
	"fmt"
	"testing"
	"time"
)

// A run of failures is counted, and the first answer ends it.
func TestHealthCountsFailuresInARow(t *testing.T) {
	var h health
	h.reset()
	for i := 0; i < 3; i++ {
		h.note(ErrFiltered)
	}
	if fails, _ := h.read(); fails != 3 {
		t.Fatalf("three filtered dials counted as %d", fails)
	}
	h.note(nil)
	if fails, since := h.read(); fails != 0 || since > time.Second {
		t.Fatalf("an answer left fails=%d since=%v", fails, since)
	}
}

// An exit that refuses one destination is answering, and must not be counted
// as failing - otherwise one dead website would read as a dead exit.
func TestHealthRefusalIsProofOfLife(t *testing.T) {
	var h health
	h.reset()
	h.note(ErrFiltered)
	h.note(fmt.Errorf("%w: HTTP/1.1 502 Bad Gateway", ErrTargetRefused))
	if fails, _ := h.read(); fails != 0 {
		t.Fatalf("a 502 from the exit was counted against it: fails=%d", fails)
	}
}
