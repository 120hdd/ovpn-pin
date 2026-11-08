package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Taking the dead ones out, and being able to put them back.
//
// app/dropped.py, on a phone. The rule it exists for is the same: a re-test
// that fails must not delete anything. The desktop learned that the hard way
// - a sweep that deleted a config from every folder claiming it worked meant
// every measuring run quietly pruned the pool it was measuring - and a phone
// has the same shape of mistake available and no recycle bin at all.
//
// So files are moved into dropped/, one shelf per folder they came from, with
// an index recording where each one belongs. A put-back is then exact rather
// than a guess.

// shelfRow is one set-aside file, remembered so it can go home.
type shelfRow struct {
	From string `json:"from"` // the folder it was taken out of
	File string `json:"file"` // its name there
	At   int64  `json:"at"`
	Why  string `json:"why"`
}

// Shelf is what one shelf holds, in the shape the sheet draws.
type Shelf struct {
	Tag   string `json:"tag"`
	Label string `json:"label"`
	Count int    `json:"count"`
}

func loadShelfIndex() map[string]shelfRow {
	out := map[string]shelfRow{}
	b, err := os.ReadFile(DroppedIndexPath())
	if err != nil {
		return out
	}
	_ = json.Unmarshal(b, &out)
	return out
}

func saveShelfIndex(index map[string]shelfRow) { _ = writeJSON(DroppedIndexPath(), index) }

// tagFor is the shelf a folder gets. One folder on a phone, so one shelf -
// but named rather than numbered, because the name is what the sheet shows
// and what a put-back is asked for by.
func tagFor(folder string) string {
	name := filepath.Base(strings.TrimRight(folder, `/\`))
	if name == "" || name == "." {
		return "pinned"
	}
	return name
}

// Shelves is what has been set aside, read off the disk rather than out of
// the index.
//
// Off the disk on purpose: a file dropped in by hand still shows, and a lost
// index row does not strand a file where nothing will ever offer to put it
// back. The index is only consulted for where a file came from.
func Shelves() []Shelf {
	entries, err := os.ReadDir(DroppedDir())
	if err != nil {
		return nil
	}
	var out []Shelf
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		files, err := os.ReadDir(filepath.Join(DroppedDir(), e.Name()))
		if err != nil {
			continue
		}
		n := 0
		for _, f := range files {
			if !f.IsDir() && strings.HasSuffix(f.Name(), ".ovpn") {
				n++
			}
		}
		if n > 0 {
			out = append(out, Shelf{Tag: e.Name(), Label: e.Name(), Count: n})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Label < out[j].Label })
	return out
}

// PutAside moves files out of a folder and onto its shelf.
//
// A name already on the shelf is refused rather than overwritten. Two
// different files under one name is a collision this cannot resolve, and
// silently keeping one of them is the wrong half of a choice nobody was
// offered.
func PutAside(folder string, files []string, why map[string]string) (moved, failed []string) {
	tag := tagFor(folder)
	shelf := filepath.Join(DroppedDir(), tag)
	if err := os.MkdirAll(shelf, 0o755); err != nil {
		return nil, files
	}
	index := loadShelfIndex()

	for _, name := range files {
		from := filepath.Join(folder, name)
		to := filepath.Join(shelf, name)
		if _, err := os.Stat(to); err == nil {
			failed = append(failed, name)
			continue
		}
		if err := os.Rename(from, to); err != nil {
			failed = append(failed, name)
			continue
		}
		index[tag+"/"+name] = shelfRow{
			From: folder, File: name, At: time.Now().Unix(),
			Why: why[BaseName(name)],
		}
		moved = append(moved, name)
	}
	saveShelfIndex(index)
	return moved, failed
}

// PutBack moves them home again.
//
// Where the index has forgotten where one came from, it goes back to the
// folder the shelf is named after if that folder exists, and nowhere at all
// if it does not - a file moved to a guessed destination is worse than one
// left where somebody can still find it.
func PutBack(tags []string) (back, stuck []string) {
	index := loadShelfIndex()
	wanted := map[string]bool{}
	for _, t := range tags {
		wanted[t] = true
	}

	for _, shelf := range Shelves() {
		if len(wanted) > 0 && !wanted[shelf.Tag] {
			continue
		}
		dir := filepath.Join(DroppedDir(), shelf.Tag)
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, f := range files {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".ovpn") {
				continue
			}
			key := shelf.Tag + "/" + f.Name()
			home := index[key].From
			if home == "" {
				home = filepath.Join(DataDir(), shelf.Tag)
				if st, err := os.Stat(home); err != nil || !st.IsDir() {
					stuck = append(stuck, f.Name())
					continue
				}
			}
			to := filepath.Join(home, f.Name())
			if _, err := os.Stat(to); err == nil {
				// Already home - somebody put it back another way, or a
				// fetch wrote it again. Drop the shelf copy rather than
				// refuse: two of one file is not a state worth keeping.
				_ = os.Remove(filepath.Join(dir, f.Name()))
				delete(index, key)
				back = append(back, f.Name())
				continue
			}
			if err := os.Rename(filepath.Join(dir, f.Name()), to); err != nil {
				stuck = append(stuck, f.Name())
				continue
			}
			delete(index, key)
			back = append(back, f.Name())
		}
		// An empty shelf is not a shelf. Removed only when it is empty, so
		// this can never take anything with it.
		_ = os.Remove(dir)
	}
	saveShelfIndex(index)
	return back, stuck
}
