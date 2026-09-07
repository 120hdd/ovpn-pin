package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// The shape of a pinned config's name, and everything that can be learned
// without opening it:
//
//	01.8s-fr-par.ws.fr-030.totallyacdn.com_146.70.253.194.ovpn
//	^^^^^ ^^ ^^^ ^^
//	  |    |   |   `- prod (Surfshark) or ws (Windscribe)
//	  |    |   `----- the city, three letters
//	  |    `--------- the country, two
//	  `-------------- how long it took when it was last swept, if it was
//
// Identical to engine.py's CONFIG, and it has to stay identical: a phone that
// grouped its exits differently from the desktop that pinned them would show a
// different world for the same folder.
var configName = regexp.MustCompile(`^(?:(\d+\.\d+)s-)?([a-zA-Z]{2})-([a-zA-Z]{3})\.(?i:prod|ws)\.`)

// windscribeMark is what tells the two providers apart. Windscribe's configs
// carry `.ws.` where Surfshark's carry `.prod.`.
const windscribeMark = ".ws."

// Catalogue is countries, not servers.
//
// Ninety-one endpoints is a list nobody reads; seventy-five countries is a
// thing people already have opinions about. Which city inside one is our
// problem, not theirs.
type Catalogue struct {
	folder string
	all    []Server

	// What was measured, and what a published list said about it. Both are
	// optional, and together they are the difference between a list that can
	// be ordered and one that can only be alphabetised - so they are attached
	// rather than read here, because the caller knows when they last changed.
	reach map[string]ReachRec
	notes map[string]WsNote

	// A narrowing by filename, which is how Starred is a pool rather than a
	// folder. Nil means everything.
	only map[string]bool
}

// Scan reads a folder of pinned configs.
//
// A config that will not parse is skipped rather than reported: the folder is
// the user's, and a stray file in it is not an error the app has anything to
// say about. A config that parses but is not pinned is a different matter and
// is caught later, at connect, where it can name itself.
func Scan(folder string) (*Catalogue, error) {
	entries, err := os.ReadDir(folder)
	if err != nil {
		return nil, err
	}

	c := &Catalogue{folder: folder}
	seen := map[string]bool{}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".ovpn") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)

	for _, name := range names {
		// The same config pinned into two folders is one exit, and counting
		// it twice would weight the race towards it.
		if seen[name] {
			continue
		}
		m := configName.FindStringSubmatch(name)
		if m == nil {
			continue
		}
		seen[name] = true

		var seconds *float64
		if m[1] != "" {
			if v, err := strconv.ParseFloat(m[1], 64); err == nil {
				seconds = &v
			}
		}
		s, err := ReadConfig(filepath.Join(folder, name))
		if err != nil {
			continue
		}
		s.File = name
		// canon, because one provider writes `uk` and the other `gb`, and
		// they are one country wherever they were pinned.
		s.Country = Canon(m[2])
		s.City = strings.ToLower(m[3])
		s.Seconds = seconds
		s.Provider = providerOf(name)
		c.all = append(c.all, s)
	}
	return c, nil
}

// Attach hands the catalogue what was measured and what was published.
//
// Kept apart from Scan because the two change at different times and for
// different reasons: a scan is about which files are there, this is about
// what is known concerning them. A test finishing has to change the ordering
// of the list without re-reading four hundred files to do it.
func (c *Catalogue) Attach(reach map[string]ReachRec, notes map[string]WsNote) {
	c.reach = reach
	c.notes = notes
}

// Only narrows the catalogue to a set of filenames, or to everything when the
// set is empty. What Starred is made of: a handful of exits spread across
// every country rather than a folder of their own.
func (c *Catalogue) Only(files map[string]bool) {
	if len(files) == 0 {
		c.only = nil
		return
	}
	c.only = files
}

func providerOf(name string) string {
	if strings.Contains(name, windscribeMark) {
		return "windscribe"
	}
	return "surfshark"
}

// Servers is what the catalogue offers, after any narrowing.
func (c *Catalogue) Servers() []Server {
	if c.only == nil {
		return c.all
	}
	out := make([]Server, 0, len(c.only))
	for _, s := range c.all {
		if c.only[s.File] {
			out = append(out, s)
		}
	}
	return out
}

// Everything is the whole scan, narrowing ignored. What a source picker
// counts against, and what a set-aside sheet has to look through.
func (c *Catalogue) Everything() []Server { return c.all }

// Count is how many are on offer.
func (c *Catalogue) Count() int { return len(c.Servers()) }

// note is what the published list said about one exit, or the empty note.
func (c *Catalogue) note(file string) WsNote { return c.notes[ConfigStem(file)] }

// cityOf is the key and the display name of the city an exit is in.
//
// The three letters come out of the filename and the name out of the notes
// beside it, so a city still groups correctly when the notes are missing - it
// just reads as its code. engine.py's city_of.
func (c *Catalogue) cityOf(s Server) (key, name string) {
	name = c.note(s.File).City
	if name == "" {
		name = CityName(s.City)
	}
	return CityKey(name), name
}

// In is every exit in one country, quickest-known first.
func (c *Catalogue) In(country string) []Server {
	country = Canon(country)
	var out []Server
	for _, s := range c.Servers() {
		if country == "" || country == "auto" || s.Country == country {
			out = append(out, s)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i].Seconds, out[j].Seconds
		switch {
		case a == nil && b == nil:
			return out[i].File < out[j].File
		case a == nil:
			return false
		case b == nil:
			return true
		default:
			return *a < *b
		}
	})
	return out
}

// Pool is the exits behind one row of the list.
//
// The same strings the page sends everywhere it names a row, and it has to
// take all of them, because the row that opens a list of exits and the row
// that tests them are the same row. main.py's _pool_for:
//
//	fr             every exit in France
//	fr/par         Paris only
//	fr:windscribe  France, one provider
//	file:x.ovpn    one config
func (c *Catalogue) Pool(code string) []Server {
	if rest, found := strings.CutPrefix(code, "file:"); found {
		var out []Server
		for _, s := range c.Servers() {
			if s.File == rest {
				out = append(out, s)
			}
		}
		return out
	}
	where, via, _ := strings.Cut(code, ":")
	where, city, _ := strings.Cut(where, "/")
	where = Canon(where)

	var out []Server
	for _, s := range c.Servers() {
		if where != "" && where != "auto" && s.Country != where {
			continue
		}
		if via != "" && s.Provider != via {
			continue
		}
		if city != "" {
			if key, _ := c.cityOf(s); key != city {
				continue
			}
		}
		out = append(out, s)
	}
	return out
}

// -- what the window reads ---------------------------------------------------
//
// These mirror the keys engine.py's catalogue() produces, name for name,
// because the page reading them is the same page. Anything the phone cannot
// measure is present and empty rather than absent: a row that has never been
// tested has to look untested, and a missing key reads as zero.

type cityRow struct {
	Code    string         `json:"code"`
	Country string         `json:"country"`
	Name    string         `json:"name"`
	Nick    string         `json:"nick"`
	Count   int            `json:"count"`
	Tested  int            `json:"tested"`
	OK      int            `json:"ok"`
	Ping    *int           `json:"ping"`
	Load    *int           `json:"load"`
	Gbps    *int           `json:"gbps"`
	P2P     *bool          `json:"p2p"`
	By      map[string]int `json:"by"`
}

type countryRow struct {
	Code     string         `json:"code"`
	Name     string         `json:"name"`
	Alias    []string       `json:"alias"`
	Cities   int            `json:"cities"`
	CityList []cityRow      `json:"cityList"`
	Count    int            `json:"count"`
	By       map[string]int `json:"by"`
	Tested   int            `json:"tested"`
	OK       int            `json:"ok"`
	Ping     *int           `json:"ping"`
	ByOK     map[string]int `json:"byOk"`
	ByTested map[string]int `json:"byTested"`
	ByPing   map[string]int `json:"byPing"`
	Best     *float64       `json:"best"`
}

// blocked is the one thing about a list of places to connect through that is
// worth reordering for: every address here was asked, and every one of them
// said no. Untested is not that, and must not sort like it.
func blocked(tested, ok int) bool { return tested > 0 && ok == 0 }

// lower is "a is a better time than b", where nothing known loses to anything
// known.
func lower(a, b *int) bool {
	switch {
	case a == nil:
		return false
	case b == nil:
		return true
	default:
		return *a < *b
	}
}

// CountriesJSON is the list the window renders, already encoded.
//
// JSON rather than a shape across the bridge, and deliberately: gomobile
// cannot carry a slice of structs at all, and the page is going to parse JSON
// whatever happens. Handing it the bytes means one encode here and one parse
// there, instead of fifteen fields mapped through two languages that would
// each have to be kept in step by hand.
func (c *Catalogue) CountriesJSON() string {
	type key struct{ country, city string }

	cities := map[key]*cityRow{}
	countries := map[string]*countryRow{}
	rawCities := map[string]map[string]bool{}
	order := []string{}

	for _, s := range c.Servers() {
		note := c.note(s.File)
		cityKey, cityName := c.cityOf(s)

		ck := key{s.Country, cityKey}
		city := cities[ck]
		if city == nil {
			city = &cityRow{
				Code: cityKey, Country: s.Country, Name: cityName,
				Nick: note.Nick, Load: note.Load, Gbps: note.Gbps,
				P2P: note.P2P, By: map[string]int{},
			}
			cities[ck] = city
		}
		city.Count++
		city.By[s.Provider]++
		// The least loaded of the group is the honest figure for a city with
		// more than one exit in it: that is the one a connection asking now
		// would be handed.
		if note.Load != nil && (city.Load == nil || *note.Load < *city.Load) {
			city.Load = note.Load
		}

		c2 := countries[s.Country]
		if c2 == nil {
			c2 = &countryRow{
				Code: s.Country, Name: CountryName(s.Country),
				Alias:    Aliases(s.Country),
				By:       map[string]int{},
				ByOK:     map[string]int{},
				ByTested: map[string]int{},
				ByPing:   map[string]int{},
			}
			countries[s.Country] = c2
			order = append(order, s.Country)
			rawCities[s.Country] = map[string]bool{}
		}
		// Counted off the filename's own three letters rather than off the
		// merged key, because that is what the desktop counts and the number
		// sits on screen beside the list it is about.
		rawCities[s.Country][s.City] = true
		c2.Count++
		// Per provider as well as in total, because a country backed by
		// eleven Surfshark exits and one Windscribe one is a different
		// proposition from the reverse, and a row that says only "12 relays"
		// cannot tell you which you are about to get.
		c2.By[s.Provider]++
		if s.Seconds != nil && (c2.Best == nil || *s.Seconds < *c2.Best) {
			v := *s.Seconds
			c2.Best = &v
		}

		// What the last test found, folded up per country and per provider.
		// Kept as separate keys rather than folded into by, so that a country
		// nobody has tested still answers the only question the list used to
		// be able to answer - how many are here.
		rec, tested := c.reach[s.File]
		if !tested {
			continue
		}
		city.Tested++
		c2.Tested++
		// Per provider as well, because "this country was tested" is not
		// "this provider's share of it was". Windscribe's exits measured and
		// Surfshark's never asked left every Surfshark row reading "blocked
		// here" - which is not a softer way of saying untested, it is the
		// opposite of true.
		c2.ByTested[s.Provider]++
		if !rec.Answered() {
			continue
		}
		city.OK++
		c2.OK++
		c2.ByOK[s.Provider]++
		if rec.Ms == nil {
			continue
		}
		if lower(rec.Ms, city.Ping) {
			city.Ping = rec.Ms
		}
		if lower(rec.Ms, c2.Ping) {
			c2.Ping = rec.Ms
		}
		if was, seen := c2.ByPing[s.Provider]; !seen || *rec.Ms < was {
			c2.ByPing[s.Provider] = *rec.Ms
		}
	}

	out := make([]countryRow, 0, len(order))
	for _, code := range order {
		row := countries[code]
		for k, v := range cities {
			if k.country == code {
				row.CityList = append(row.CityList, *v)
			}
		}
		// Answering first, then quickest, then by name - so opening a country
		// shows the city worth taking at the top rather than whichever is
		// alphabetically first.
		sort.Slice(row.CityList, func(i, j int) bool {
			a, b := row.CityList[i], row.CityList[j]
			if ab, bb := blocked(a.Tested, a.OK), blocked(b.Tested, b.OK); ab != bb {
				return !ab
			}
			if (a.Ping == nil) != (b.Ping == nil) {
				return a.Ping != nil
			}
			if a.Ping != nil && *a.Ping != *b.Ping {
				return *a.Ping < *b.Ping
			}
			return a.Name < b.Name
		})
		row.Cities = len(rawCities[code])
		out = append(out, *row)
	}

	// Answering first, then quickest, then the rest.
	//
	// A country every one of whose addresses was refused belongs at the
	// bottom whatever its name. Untested sits between the two - not known to
	// be blocked, not known to be quick - and an unmeasured exit is still not
	// a fast one.
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if ab, bb := blocked(a.Tested, a.OK), blocked(b.Tested, b.OK); ab != bb {
			return !ab
		}
		if (a.Ping == nil) != (b.Ping == nil) {
			return a.Ping != nil
		}
		if a.Ping != nil && *a.Ping != *b.Ping {
			return *a.Ping < *b.Ping
		}
		if (a.Best == nil) != (b.Best == nil) {
			return a.Best != nil
		}
		if a.Best != nil && *a.Best != *b.Best {
			return *a.Best < *b.Best
		}
		return a.Name < b.Name
	})

	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// exitRow is one individual exit and what is known about it. engine.py's
// exits(), key for key.
type exitRow struct {
	File     string `json:"file"`
	City     string `json:"city"`
	CityName string `json:"cityName"`
	Nick     string `json:"nick"`
	Load     *int   `json:"load"`
	Gbps     *int   `json:"gbps"`
	P2P      *bool  `json:"p2p"`
	Provider string `json:"provider"`
	Host     string `json:"host"`
	IP       string `json:"ip"`
	OK       *bool  `json:"ok"`
	Ms       *int   `json:"ms"`
	Why      string `json:"why"`
	At       *int64 `json:"at"`
}

// ExitsJSON is every exit behind one row, with its verdict.
//
// The list has always been countries, because ninety-one endpoints is not
// something anybody reads. But once each one has a measured answer and a time
// beside it, the individual exits are worth being able to look at: "why is
// this country slow" and "is this one blocked" are questions about a server,
// not about a place.
func (c *Catalogue) ExitsJSON(code string) string {
	pool := c.Pool(code)
	rows := make([]exitRow, 0, len(pool))
	for _, s := range pool {
		note := c.note(s.File)
		_, cityName := c.cityOf(s)
		row := exitRow{
			File: s.File, City: s.City, CityName: cityName, Nick: note.Nick,
			Load: note.Load, Gbps: note.Gbps, P2P: note.P2P,
			Provider: s.Provider, Host: s.Name, IP: s.Addr,
		}
		if rec, ok := c.reach[s.File]; ok {
			row.OK, row.Ms, row.Why = rec.OK, rec.Ms, rec.Why
			at := rec.At
			row.At = &at
		}
		rows = append(rows, row)
	}
	// Answering first and quickest first; untested after those, refused last.
	// The same order as the countries, for the same reason.
	sort.Slice(rows, func(i, j int) bool {
		a, b := rows[i], rows[j]
		af, bf := a.OK != nil && !*a.OK, b.OK != nil && !*b.OK
		if af != bf {
			return !af
		}
		if (a.OK == nil) != (b.OK == nil) {
			return a.OK != nil
		}
		am, bm := 0, 0
		if a.Ms != nil {
			am = *a.Ms
		}
		if b.Ms != nil {
			bm = *b.Ms
		}
		if am != bm {
			return am < bm
		}
		return a.Host < b.Host
	})

	b, err := json.Marshal(struct {
		OK    bool      `json:"ok"`
		Exits []exitRow `json:"exits"`
	}{true, rows})
	if err != nil {
		return `{"ok":true,"exits":[]}`
	}
	return string(b)
}

// NameParts is the country and city a filename claims, without opening it or
// needing the file to still be there.
//
// The set-aside sheet needs this: its rows are about configs that have been
// moved out of the folder, so there is no catalogue entry left to ask. All of
// it is in the name, which is the whole point of the naming scheme.
func NameParts(name string) (country, city string, ok bool) {
	m := configName.FindStringSubmatch(name)
	if m == nil {
		return "", "", false
	}
	return Canon(m[2]), strings.ToLower(m[3]), true
}

// NoteCity is the published name for the city a config is in, or "" when
// nothing was published about it.
func (c *Catalogue) NoteCity(file string) string { return c.note(file).City }
