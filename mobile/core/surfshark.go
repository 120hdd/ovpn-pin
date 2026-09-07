package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Surfshark's published fleet, and configs written from it.
//
// There is no sign-in here, and that is not an omission. The cluster list is
// public and unauthenticated; the "account" is a service username and
// password that the exits themselves check, issued on the same page as the
// config files and separate from the login email. Nothing in this file has a
// credential in it.

const (
	surfsharkHost = "api.surfshark.com"
	surfsharkURL  = "https://api.surfshark.com/v4/server/clusters"

	// A browser's, because an API that is being reached around a filter is
	// not the place to be interesting.
	browserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
)

// Only the ones whose name is the shape a config is written from. The list
// carries entries this app has no use for, and a name that does not match
// this cannot be turned into a filename the catalogue will read back.
var surfsharkName = regexp.MustCompile(`^[a-z]{2}-[a-z]{3}\.prod\.surfshark\.com$`)

// The remote line, found anywhere in a file rather than at the start of one.
// config.go has its own because it reads a config a line at a time; this one
// rewrites a whole template, so it has to be multi-line and has to keep hold
// of what surrounds the name - the indentation before it and the port and
// protocol after.
var remoteAnywhere = regexp.MustCompile(`(?m)^([ \t]*remote[ \t]+)(\S+)(.*)$`)

// An address rather than a name, which is what tells a pinned config from an
// unpinned one without opening the question of what a hostname looks like.
var dottedQuad = regexp.MustCompile(`^\d{1,3}(?:\.\d{1,3}){3}$`)

// swapRemote puts a new host on the first remote line and leaves the rest of
// the file alone - including any further remote lines, which are alternatives
// rather than the one this config is about.
func swapRemote(text, host string) string {
	at := remoteAnywhere.FindStringSubmatchIndex(text)
	if at == nil {
		return text
	}
	// at[4]:at[5] is the host itself; everything either side is kept.
	return text[:at[4]] + host + text[at[5]:]
}

// The whole of the fallback config, for a folder with nothing to copy from.
//
// Six lines, because this app never runs OpenVPN against these exits - it
// speaks to the proxy on 443, and the address is the entire content. The port
// on the remote line is OpenVPN's and is decorative here; it is kept so the
// file is still a config somebody could use elsewhere.
const surfsharkStub = `# Surfshark exit, written from the published cluster list.
# This app talks to the proxy on 443 and never runs OpenVPN
# against it, so the address is the whole of the content.
client
dev tun
proto tcp
remote %s 1443
`

// SurfsharkServer is one row of the published list, as this app uses it.
type SurfsharkServer struct {
	Hostname    string `json:"hostname"`
	Country     string `json:"country"`
	Code        string `json:"code"`
	Place       string `json:"place"`
	CountryName string `json:"countryName"`
	Load        int    `json:"load"`
}

// SurfsharkServers is the published cluster list, filtered to what can be
// written as a config.
func SurfsharkServers(ctx context.Context, timeout time.Duration) ([]SurfsharkServer, error) {
	status, body, err := Fetch(ctx, surfsharkHost, surfsharkURL, "GET",
		map[string]string{
			"Accept":     "application/json",
			"User-Agent": browserAgent,
		}, nil, timeout)
	if err != nil {
		return nil, err
	}
	if status < 200 || status > 299 {
		return nil, fmt.Errorf("Surfshark answered %d", status)
	}

	var rows []struct {
		ConnectionName string  `json:"connectionName"`
		Location       string  `json:"location"`
		Country        string  `json:"country"`
		Load           float64 `json:"load"`
	}
	if err := json.Unmarshal(body, &rows); err != nil {
		return nil, errors.New("the cluster list did not parse")
	}

	out := make([]SurfsharkServer, 0, len(rows))
	for _, r := range rows {
		name := strings.ToLower(strings.TrimSpace(r.ConnectionName))
		if !surfsharkName.MatchString(name) {
			continue
		}
		// The country and the city come out of the hostname rather than out
		// of the JSON, deliberately: the filename is built from them and the
		// catalogue reads them back out of the filename, so the two have to be
		// the same two letters.
		out = append(out, SurfsharkServer{
			Hostname:    name,
			Country:     name[:2],
			Code:        name[3:6],
			Place:       r.Location,
			CountryName: r.Country,
			Load:        int(r.Load),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Hostname < out[j].Hostname })
	return out, nil
}

// WriteSurfsharkConfigs fetches the list and writes what is missing.
//
// Additive only. A folder is somebody's, and a config that is no longer in
// the published list is not necessarily one they want gone - it may be the
// only record of an exit that still works.
func WriteSurfsharkConfigs(ctx context.Context, dest string, timeout time.Duration) (
	folder string, total, countries, added, kept int, err error) {

	servers, err := SurfsharkServers(ctx, timeout)
	if err != nil {
		return "", 0, 0, 0, 0, err
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return "", 0, 0, 0, 0, err
	}

	// A config already in the folder, with its remote still a name, is the
	// best template there is: it carries this account's certificate blocks
	// and cipher lines. A pinned one would carry an address, which is the one
	// thing being replaced.
	template := surfsharkTemplate(dest)

	seen := map[string]bool{}
	for _, s := range servers {
		seen[s.Country] = true
		name := s.Hostname + "_tcp.ovpn"
		path := filepath.Join(dest, name)
		if _, statErr := os.Stat(path); statErr == nil {
			kept++
			continue
		}
		text := fmt.Sprintf(surfsharkStub, s.Hostname)
		if template != "" {
			text = swapRemote(template, s.Hostname)
		}
		if writeErr := os.WriteFile(path, []byte(text), 0o644); writeErr != nil {
			continue
		}
		added++
	}

	saveSurfsharkMeta(servers)
	return dest, len(servers), len(seen), added, kept, nil
}

// surfsharkTemplate is an existing unpinned config to copy, or "".
func surfsharkTemplate(dest string) string {
	entries, err := os.ReadDir(dest)
	if err != nil {
		return ""
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".ovpn") {
			continue
		}
		b, readErr := os.ReadFile(filepath.Join(dest, e.Name()))
		if readErr != nil {
			continue
		}
		text := string(b)
		m := remoteAnywhere.FindStringSubmatch(text)
		// Not a pinned one: its remote has to still be a name, or the
		// template would carry one exit address into every file written
		// from it.
		if m == nil || dottedQuad.MatchString(m[2]) {
			continue
		}
		return text
	}
	return ""
}

func saveSurfsharkMeta(servers []SurfsharkServer) {
	rows := map[string]SsNote{}
	for _, s := range servers {
		load := s.Load
		rows[s.Hostname] = SsNote{Place: s.Place, Country: s.CountryName, Load: &load}
	}
	_ = writeJSON(SurfsharkMetaPath(), struct {
		Fetched int64             `json:"fetched"`
		Servers map[string]SsNote `json:"servers"`
	}{time.Now().Unix(), rows})
}
