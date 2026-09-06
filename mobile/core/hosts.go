package core

import (
	"encoding/json"
	"sort"
	"sync"
	"time"
)

// What went where, counted as it goes.
//
// The desktop's proxy writes this to a file every other second and the window
// reads it back; there is no file here and no second process to write one -
// the thing carrying the bytes is in the same address space as the thing
// being asked. So it is counted in memory, at the one place every connection
// already passes through.
//
// What a row is: one destination, as the program asked for it. Not resolved,
// not reverse-looked-up, not tidied. A hostname in here is whatever something
// on this phone asked for, and it reaches the page as text to put in a list -
// the same rule the desktop states, and it matters more here, because on a
// phone the asking program is usually not the one reading the list.
//
// There is no `app` column. The desktop knows which program opened a socket
// because it can look up the owner of a local port; an Android app cannot
// see another app's sockets, and the tun hands over packets rather than
// processes. Left null rather than guessed at - the page already draws a row
// without one, and a wrong name would be worse than none.
type Hosts struct {
	mu    sync.Mutex
	rows  map[string]*hostRow
	since time.Time

	// Capped, because a phone left connected overnight talking to a hundred
	// analytics endpoints should not grow a map until something notices. The
	// least recently seen goes when the cap is reached, which is the row
	// least likely to be the one somebody opened the sheet to find.
	limit int
}

type hostRow struct {
	// What the sheet shows: the name it was looked up under when this app
	// answered that lookup, and the address otherwise. The desktop's `host`
	// is a name because its proxy is asked for one by name; here the name has
	// to be remembered, and when it was not there is nothing honest to show
	// but the address.
	Host string `json:"host"`
	// The address, always, whatever Host says. Kept because it is what the
	// row is really about and what two names for one CDN would collapse.
	Addr  string  `json:"addr"`
	App   *string `json:"app"`
	Pid   int     `json:"pid"`
	Up    int64   `json:"up"`
	Down  int64   `json:"down"`
	Hits  int     `json:"hits"`
	Live  int     `json:"live"`
	First float64 `json:"first"`
	Last  float64 `json:"last"`
}

const hostsLimit = 400

func newHosts() *Hosts {
	return &Hosts{rows: map[string]*hostRow{}, since: time.Now(), limit: hostsLimit}
}

// opened records a connection starting.
//
// Keyed on the address rather than on the label: the address is what the
// connection is actually to, and two rows for one destination because a name
// arrived late would be two rows nobody asked for.
func (h *Hosts) opened(addr, label, app string) *hostRow {
	if h == nil {
		return nil
	}
	h.mu.Lock()
	defer h.mu.Unlock()

	if len(addr) > 120 {
		addr = addr[:120]
	}
	if len(label) > 120 {
		label = label[:120]
	}
	now := float64(time.Now().UnixNano()) / 1e9

	row := h.rows[addr]
	if row == nil {
		if len(h.rows) >= h.limit {
			h.evictOldest()
		}
		row = &hostRow{Host: addr, Addr: addr, First: now}
		h.rows[addr] = row
	}
	// A name learned later fills in a row that started without one: an app
	// can dial an address before anything looked it up, and the lookup that
	// explains it may land a second afterwards.
	if label != "" && row.Host == row.Addr {
		row.Host = label
	}
	// Same rule for the program: filled in the first time the platform will
	// say, and never overwritten - two apps sharing a destination is real,
	// and the first one to reach it is a truer answer than the last.
	if app != "" && row.App == nil {
		name := app
		row.App = &name
	}
	row.Hits++
	row.Live++
	row.Last = now
	return row
}

// rename gives a row the name its own traffic announced.
//
// Separate from opened because the name arrives later: the connection has to
// exist before the client can say anything on it. Only ever fills in a row
// still labelled by address, so the first name wins and a second connection
// to a shared address cannot relabel the first one.
func (h *Hosts) rename(addr, label string) {
	if h == nil || label == "" {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if row := h.rows[addr]; row != nil && row.Host == row.Addr {
		if len(label) > 120 {
			label = label[:120]
		}
		row.Host = label
	}
}

// closed records a connection ending, with what it carried.
func (h *Hosts) closed(row *hostRow, up, down int64) {
	if h == nil || row == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if row.Live > 0 {
		row.Live--
	}
	row.Up += up
	row.Down += down
	row.Last = float64(time.Now().UnixNano()) / 1e9
}

// evictOldest drops the least recently seen row. Called with the lock held.
func (h *Hosts) evictOldest() {
	var oldest string
	var when float64
	for k, v := range h.rows {
		// A connection still open is never the one to drop: it is the row
		// most likely to be growing, and dropping it would lose the count
		// its own copiers are still adding to.
		if v.Live > 0 {
			continue
		}
		if oldest == "" || v.Last < when {
			oldest, when = k, v.Last
		}
	}
	if oldest != "" {
		delete(h.rows, oldest)
	}
}

// JSON is the shape the log sheet reads, and the desktop's shape exactly.
func (h *Hosts) JSON() string {
	if h == nil {
		return `{"live":false,"rows":[]}`
	}
	h.mu.Lock()
	rows := make([]hostRow, 0, len(h.rows))
	for _, v := range h.rows {
		rows = append(rows, *v)
	}
	since := float64(h.since.UnixNano()) / 1e9
	h.mu.Unlock()

	// Busiest first. The sheet sorts for itself, but a truncated list should
	// be truncated at the boring end.
	sort.Slice(rows, func(i, j int) bool {
		return rows[i].Up+rows[i].Down > rows[j].Up+rows[j].Down
	})

	out := map[string]any{
		"live":  true,
		"rows":  rows,
		"total": len(rows),
		"since": since,
		"at":    float64(time.Now().UnixNano()) / 1e9,
	}
	b, err := json.Marshal(out)
	if err != nil {
		return `{"live":false,"rows":[]}`
	}
	return string(b)
}
