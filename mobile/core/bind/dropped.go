package relay

import (
	"encoding/json"
	"path/filepath"
	"strings"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// What would be set aside, what already has been, and doing either.
//
// Everything here is about exits the last test refused, and about them only:
// `ok` false and nothing else. `ok` null is what a second look leaves on an
// exit it asked twice and could not judge, and treating "we could not tell"
// as "it is dead" is how one bad thirty seconds on this line used to cost a
// provider its whole fleet.

type deadRow struct {
	File     string `json:"file"`
	Folder   string `json:"folder"`
	Tag      string `json:"tag"`
	Country  string `json:"country"`
	City     string `json:"city"`
	Provider string `json:"provider"`
	IP       string `json:"ip"`
	Why      string `json:"why"`
	At       int64  `json:"at"`
}

type deadFolder struct {
	Path  string `json:"path"`
	Tag   string `json:"tag"`
	Label string `json:"label"`
	Count int    `json:"count"`
}

// dead is every exit the store says was refused, with the row the sheet draws.
func (c *Client) dead() ([]deadRow, map[string]string) {
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()

	rows := []deadRow{}
	why := map[string]string{}
	if cat == nil {
		return rows, why
	}

	reach := core.LoadReach()
	folder := core.PinnedDir()
	tag := filepath.Base(folder)

	for _, s := range cat.Everything() {
		rec, tested := reach[s.File]
		// Refused, not merely unmeasured. That distinction is the whole
		// safety of this sheet.
		if !tested || rec.OK == nil || *rec.OK {
			continue
		}
		city := cat.NoteCity(s.File)
		if city == "" {
			city = core.CityName(s.City)
		}
		rows = append(rows, deadRow{
			File: s.File, Folder: folder, Tag: tag,
			Country: s.Country, City: city, Provider: s.Provider,
			IP: rec.IP, Why: rec.Why, At: rec.At,
		})
		why[core.BaseName(s.File)] = rec.Why
	}
	return rows, why
}

// DeadExitsJSON is what would go, where from, and why - without anything
// going.
//
// The mark in the header opens this and nothing else. Which folder to take a
// config out of is a judgement on the desktop, where the same exit sits in
// three folders that mean different things; a phone has one, so the sheet has
// one chip - but the shape is the desktop's, because the sheet drawing it is.
func (c *Client) DeadExitsJSON() (out string) {
	defer said(&out)

	rows, _ := c.dead()

	shelves := core.Shelves()
	if shelves == nil {
		shelves = []core.Shelf{}
	}
	aside := 0
	for _, s := range shelves {
		aside += s.Count
	}

	folders := []deadFolder{}
	if len(rows) > 0 {
		folder := core.PinnedDir()
		tag := filepath.Base(folder)
		folders = append(folders, deadFolder{
			Path: folder, Tag: tag, Label: tag, Count: len(rows),
		})
	}

	// Exits, not rows. The same config in two folders is two files and one
	// exit, and the header says how many stopped answering - which is a fact
	// about exits.
	seen := map[string]bool{}
	for _, r := range rows {
		seen[core.BaseName(r.File)] = true
	}

	b, err := json.Marshal(struct {
		OK      bool         `json:"ok"`
		Folders []deadFolder `json:"folders"`
		Exits   []deadRow    `json:"exits"`
		Dead    int          `json:"dead"`
		Shelved []core.Shelf `json:"shelved"`
		Aside   int          `json:"aside"`
	}{true, folders, rows, len(seen), shelves, aside})
	if err != nil {
		return `{"ok":true,"folders":[],"exits":[],"dead":0,"shelved":[],"aside":0}`
	}
	return string(b)
}

// DropExits moves the dead ones out of the folders that were ticked.
//
// Only the folder DeadExitsJSON just offered, and only the files it named. A
// path arriving from the page that is not that one is refused rather than
// acted on: this is the single call in the bridge that moves somebody's
// configs about, and "whatever the page said" is not a good enough reason to.
func (c *Client) DropExits(folders string) (out string) {
	defer said(&out)

	wanted := map[string]bool{}
	for _, line := range lines(folders) {
		wanted[filepath.Clean(line)] = true
	}
	if len(wanted) == 0 {
		return refused("Nothing was ticked.")
	}

	folder := core.PinnedDir()
	if !wanted[filepath.Clean(folder)] {
		return refused("Those folders hold none of them any more.")
	}

	rows, why := c.dead()
	if len(rows) == 0 {
		return refused("Those folders hold none of them any more.")
	}
	names := make([]string, 0, len(rows))
	for _, r := range rows {
		names = append(names, r.File)
	}

	moved, failed := core.PutAside(folder, names, why)
	if len(moved) == 0 && len(failed) == 0 {
		return refused("Those folders hold none of them any more.")
	}
	// The folder is shorter now, so the list is a different list.
	_ = c.Load()

	b, err := json.Marshal(struct {
		OK      bool     `json:"ok"`
		Moved   int      `json:"moved"`
		Failed  int      `json:"failed"`
		Folders []string `json:"folders"`
	}{true, len(moved), len(failed), []string{filepath.Base(folder)}})
	if err != nil {
		return refused(err.Error())
	}
	return string(b)
}

// RestoreDropped puts them back where they came from.
func (c *Client) RestoreDropped(tags string) (out string) {
	defer said(&out)

	back, stuck := core.PutBack(lines(tags))
	if len(back) == 0 && len(stuck) == 0 {
		return refused("There is nothing set aside.")
	}
	_ = c.Load()

	b, err := json.Marshal(struct {
		OK    bool `json:"ok"`
		Back  int  `json:"back"`
		Stuck int  `json:"stuck"`
	}{true, len(back), len(stuck)})
	if err != nil {
		return refused(err.Error())
	}
	return string(b)
}

// lines is the newline list gomobile makes everyone use in place of a slice,
// read back into one.
func lines(text string) []string {
	var out []string
	for _, line := range strings.Split(text, "\n") {
		if s := strings.TrimSpace(line); s != "" {
			out = append(out, s)
		}
	}
	return out
}
