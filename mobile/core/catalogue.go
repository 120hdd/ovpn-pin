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
	folder  string
	servers []Server
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
		c.servers = append(c.servers, s)
	}
	return c, nil
}

func providerOf(name string) string {
	if strings.Contains(name, windscribeMark) {
		return "windscribe"
	}
	return "surfshark"
}

func (c *Catalogue) Servers() []Server { return c.servers }
func (c *Catalogue) Count() int        { return len(c.servers) }

// In is every exit in one country, quickest-known first.
func (c *Catalogue) In(country string) []Server {
	country = Canon(country)
	var out []Server
	for _, s := range c.servers {
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

// CountriesJSON is the list the window renders, already encoded.
//
// JSON rather than a struct across the bridge, and deliberately: gomobile
// cannot carry a slice of structs at all, and the page is going to parse JSON
// whatever happens. Handing it the bytes means one encode here and one parse
// there, instead of fifteen fields mapped through two languages that would
// each have to be kept in step by hand.
func (c *Catalogue) CountriesJSON() string {
	type key struct{ country, city string }

	cities := map[key]*cityRow{}
	countries := map[string]*countryRow{}
	order := []string{}

	for _, s := range c.servers {
		ck := key{s.Country, s.City}
		city := cities[ck]
		if city == nil {
			city = &cityRow{
				Code: s.City, Country: s.Country,
				Name: CityName(s.City), By: map[string]int{},
			}
			cities[ck] = city
		}
		city.Count++
		city.By[s.Provider]++

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
		}
		c2.Count++
		c2.By[s.Provider]++
		if s.Seconds != nil && (c2.Best == nil || *s.Seconds < *c2.Best) {
			v := *s.Seconds
			c2.Best = &v
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
		// Quickest first inside a country, so that opening one shows the city
		// worth taking at the top rather than whichever is alphabetically
		// first. Nothing here is measured yet, so it falls through to name.
		sort.Slice(row.CityList, func(i, j int) bool {
			return row.CityList[i].Name < row.CityList[j].Name
		})
		row.Cities = len(row.CityList)
		out = append(out, *row)
	}

	// By name. The desktop sorts by what answered first and how quickly, and
	// this will too once the phone has swept - until then a stable
	// alphabetical list is honest about knowing nothing.
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })

	b, err := json.Marshal(out)
	if err != nil {
		return "[]"
	}
	return string(b)
}
