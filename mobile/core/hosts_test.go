package core

import (
	"bytes"
	"io"
	"strings"
	"testing"
)

// The meter has to move while a connection is still open: a long download
// is one connection, and counting it at close showed 1 MB for 50.
func TestHostsCountWhileOpen(t *testing.T) {
	h := newHosts()
	row := h.opened("1.2.3.4:443", "", "")

	var sink bytes.Buffer
	toApp := counted{w: &sink, h: h, row: row, down: true}
	if _, err := io.Copy(toApp, strings.NewReader(strings.Repeat("x", 100000))); err != nil {
		t.Fatal(err)
	}
	toExit := counted{w: &sink, h: h, row: row}
	toExit.Write([]byte("hello"))

	if row.Down != 100000 || row.Up != 5 || row.Live != 1 {
		t.Fatalf("still open: down %d up %d live %d", row.Down, row.Up, row.Live)
	}
	h.closed(row)
	if row.Down != 100000 || row.Up != 5 || row.Live != 0 {
		t.Fatalf("closed: down %d up %d live %d", row.Down, row.Up, row.Live)
	}
}
