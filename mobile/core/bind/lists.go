package relay

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// What the list is made of, on the phone's side of the bridge.
//
// Everything here answers a question the window asks by name, and answers it
// in the shape app/main.py answers it - because it is the same window. Where
// a name here looks odd, it is because the desktop's is odd and matching it
// was worth more than improving it.

// guard turns a panic into an error.
//
// Not defensive programming. A Go panic inside a JNI library takes the whole
// application down with no Java stack, nothing in logcat that names this
// package, and no dialog - the app simply closes. That happened once already,
// to a sing-tun option validated by panicking rather than by returning, and
// it cost a day. Every door into this package is held open this way so that
// the next thing to be wrong is readable instead of fatal.
func guard(errp *error) {
	if r := recover(); r != nil {
		*errp = fmt.Errorf("relay core: %v", r)
	}
}

// said is the same thing for a function that answers with JSON: the failure
// goes back as an answer the page can draw, because a rejected promise with
// no sentence in it is a spinner that never stops.
func said(out *string) {
	if r := recover(); r != nil {
		b, _ := json.Marshal(map[string]any{
			"ok": false, "error": fmt.Sprintf("relay core: %v", r)})
		*out = string(b)
	}
}

// SetDataDir names the one directory Android gave this app, and makes the
// folders under it.
//
// Called by the Activity and by the service, because either can be first:
// a phone that comes back with the tunnel still on starts the service alone,
// with no window anywhere.
func SetDataDir(dir string) (err error) {
	defer guard(&err)
	core.SetDataDir(dir)
	return core.EnsureDirs()
}

// Load reads the folder the app races from, with everything known about it.
//
// One call rather than three, because the three are never useful apart: a
// catalogue with no verdicts cannot be ordered, and verdicts with no
// catalogue are a file of filenames.
func (c *Client) Load() (err error) {
	defer guard(&err)
	return c.ScanFolder(core.PinnedDir())
}

// SetOnly narrows what this client offers to a set of filenames, one per
// line. Empty means everything.
//
// This is what Starred is: not a folder, and not a copy of anything - a
// handful of exits spread across every country, chosen by somebody, and
// raced as if they were the whole list.
func (c *Client) SetOnly(files string) (err error) {
	defer guard(&err)

	only := map[string]bool{}
	for _, name := range lines(files) {
		only[name] = true
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cat == nil {
		return nil
	}
	c.cat.Only(only)
	c.servers = c.cat.Servers()
	return nil
}

// ExitsInJSON is every exit behind one row of the list, with its verdict.
//
// The page asks for this when a country is opened. It sends the row's own
// code, which is a country, or a city inside one, or either narrowed to a
// provider - see core.Catalogue.Pool.
func (c *Client) ExitsInJSON(code string) (out string) {
	defer said(&out)
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return `{"ok":true,"exits":[]}`
	}
	return cat.ExitsJSON(code)
}

// PoolFiles is the filenames behind one row, one per line.
//
// The same selection ExitsInJSON draws, handed back as names so that Kotlin
// can count them, tick them, set them aside or feed them to SetOnly without
// having to know how a row code is parsed. One parser, in one place.
func (c *Client) PoolFiles(code string) (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = ""
		}
	}()
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return ""
	}
	pool := cat.Pool(code)
	names := make([]string, 0, len(pool))
	for _, s := range pool {
		names = append(names, s.File)
	}
	return strings.Join(names, "\n")
}

// FileInfoJSON is what a filename says about itself.
//
// For the rows in the set-aside sheet, which are about files that have been
// moved out of the folder and are therefore no longer in the catalogue. All
// of it is in the name - that is the whole point of the naming scheme - so
// nothing here opens the file.
func (c *Client) FileInfoJSON(name string) (out string) {
	defer said(&out)

	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()

	info := struct {
		OK       bool   `json:"ok"`
		File     string `json:"file"`
		Base     string `json:"base"`
		Stem     string `json:"stem"`
		Country  string `json:"country"`
		City     string `json:"city"`
		CityName string `json:"cityName"`
		Provider string `json:"provider"`
	}{
		OK: true, File: name,
		Base: core.BaseName(name), Stem: core.ConfigStem(name),
	}
	if core.IsWindscribe(name) {
		info.Provider = "windscribe"
	} else {
		info.Provider = "surfshark"
	}
	if country, city, found := core.NameParts(name); found {
		info.Country, info.City = country, city
		info.CityName = core.CityName(city)
	}
	// The published name beats the three letters when there is one, the same
	// way the list itself prefers it.
	if cat != nil && info.City != "" {
		if published := cat.NoteCity(name); published != "" {
			info.CityName = published
		}
	}

	b, err := json.Marshal(info)
	if err != nil {
		return `{"ok":false,"error":"could not describe that name"}`
	}
	return string(b)
}

// ScanEdgesJSON measures which Cloudflare addresses will carry the domain
// from this line, and answers in the shape the tunnel pane reads.
//
// The desktop's rescanEdges returns the count as well as the addresses, and
// the pane prints both: one address answering out of fifty and forty
// answering out of fifty are both "it works", and they are not the same
// weather.
func ScanEdgesJSON(domain string, timeoutMs int) (out string) {
	defer said(&out)
	return core.EdgeScanJSON(domain, ms(timeoutMs))
}

// ms is the one conversion this bridge does over and over. gomobile carries
// no time.Duration, so every timeout crosses as a plain integer and is turned
// back here rather than at twenty call sites.
func ms(n int) time.Duration { return time.Duration(n) * time.Millisecond }

// CountsJSON is how much is in a pool, without switching to it.
//
// What a source picker needs and nothing else: a row offering a pool has to
// say how big it is before anybody chooses it, and the answer must not depend
// on which pool is currently in use. So it counts against the whole scan
// rather than against the narrowing, and takes the filenames it is asking
// about rather than reading a setting.
//
// Empty files means everything.
func (c *Client) CountsJSON(files string) (out string) {
	defer said(&out)

	only := map[string]bool{}
	for _, name := range lines(files) {
		only[name] = true
	}

	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return `{"ok":true,"places":0,"exits":0}`
	}

	places := map[string]bool{}
	exits := 0
	for _, s := range cat.Everything() {
		if len(only) > 0 && !only[s.File] {
			continue
		}
		places[s.Country] = true
		exits++
	}

	b, err := json.Marshal(struct {
		OK     bool `json:"ok"`
		Places int  `json:"places"`
		Exits  int  `json:"exits"`
	}{true, len(places), exits})
	if err != nil {
		return `{"ok":true,"places":0,"exits":0}`
	}
	return string(b)
}

// EverythingFiles is every filename the folder holds, narrowing ignored.
//
// The set-aside sheet works over all of them: a file being outside the pool
// somebody is connecting from does not make it any less dead.
func (c *Client) EverythingFiles() (out string) {
	defer func() {
		if r := recover(); r != nil {
			out = ""
		}
	}()
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return ""
	}
	all := cat.Everything()
	names := make([]string, 0, len(all))
	for _, s := range all {
		names = append(names, s.File)
	}
	return strings.Join(names, "\n")
}
