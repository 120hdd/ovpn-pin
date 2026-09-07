package core

import (
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Windscribe, which has a sign-in and a puzzle in front of it.
//
// app/windscribe.py, byte for byte where it matters. Every constant here was
// read out of the browser extension rather than guessed at, and the ones that
// look arbitrary are the ones that fail silently when they are wrong:
// omitting `platform` gets "Unexpected client version. Please update your
// app."; sending a form body instead of JSON arrives with no parameters at
// all; asking for the wrong session type gets a credential the proxy refuses
// with 407.

const (
	windscribeHost = "api.windscribe.com"
	windscribeAPI  = "https://api.windscribe.com/"
	serverListURL  = "https://assets.windscribe.com/serverlist/firefox/1/1"

	// Two constants the extension carries. They are not secrets - they are in
	// a file anybody can read - and they are what the API checks a client by.
	sharedKey      = "952b4412f002315aa50751032fcaab03"
	signatureToken = "if_you_copy_this_you_might_die_a_painful_death"

	// 2 is the extension. 3 is the desktop client and 1 is OpenVPN, and a
	// credential issued for either of those is refused by the proxy with 407
	// - which reads as a wrong password and is not one.
	sessionTypeExtension = 2

	windscribeOrigin = "chrome-extension://hnmpcagpplmpfojmgmnngilcnanddlhb"
	windscribeAgent  = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
		"AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

	rateLimited     = 707
	captchaRequired = 708
)

// ApiError is Windscribe saying no, with the code it said it by.
type ApiError struct {
	Message     string
	Code        int
	Description string
}

func (e *ApiError) Error() string { return e.Message }

// ask is one call to the API, built the way the extension builds them.
//
// The query is not decoration. `platform` and a hash of the shared key over
// the current second are on every request, including the POSTs, and a request
// without them is answered by a version check rather than by the endpoint.
func ask(ctx context.Context, path string, query map[string]string,
	payload any, timeout time.Duration) (map[string]any, error) {

	now := strconv.FormatInt(time.Now().Unix(), 10)
	sum := md5.Sum([]byte(sharedKey + now))

	q := url.Values{}
	q.Set("platform", "chrome")
	q.Set("time", now)
	q.Set("client_auth_hash", hex.EncodeToString(sum[:]))
	for k, v := range query {
		q.Set(k, v)
	}

	headers := map[string]string{
		"Accept":     "application/json",
		"User-Agent": windscribeAgent,
		"Origin":     windscribeOrigin,
	}
	method := "GET"
	var body []byte
	if payload != nil {
		method = "POST"
		b, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		body = b
		// text/plain, and it is load-bearing. The extension posts
		// JSON.stringify through fetch with no content type and the API parses
		// the body itself; announcing a form encoding arrives with no
		// parameters at all.
		headers["Content-Type"] = "text/plain;charset=UTF-8"
	}

	status, answer, err := Fetch(ctx, windscribeHost,
		windscribeAPI+path+"?"+q.Encode(), method, headers, body, timeout)
	if err != nil {
		return nil, err
	}

	var out map[string]any
	if err := json.Unmarshal(answer, &out); err != nil {
		return nil, fmt.Errorf("Windscribe answered %d with something that was "+
			"not JSON", status)
	}
	// An error still arrives as a body with a 403 on it, which is how both
	// "solve the captcha" and "wrong password" come back - so the status is
	// not the thing to read, the errorCode is.
	if raw, has := out["errorCode"]; has {
		code := 0
		if n, ok := raw.(float64); ok {
			code = int(n)
		}
		message, _ := out["errorMessage"].(string)
		description, _ := out["errorDescription"].(string)
		if message == "" {
			message = fmt.Sprintf("Windscribe refused this (%d)", code)
		}
		return nil, &ApiError{Message: message, Code: code, Description: description}
	}
	return out, nil
}

func dataOf(body map[string]any) map[string]any {
	if d, ok := body["data"].(map[string]any); ok {
		return d
	}
	return map[string]any{}
}

func str(m map[string]any, key string) string {
	s, _ := m[key].(string)
	return s
}

// Captcha is the puzzle, in the shape the page draws it.
type Captcha struct {
	Kind       string `json:"kind"`
	Art        string `json:"art,omitempty"`
	Background string `json:"background,omitempty"`
	Slider     string `json:"slider,omitempty"`
	Top        int    `json:"top,omitempty"`
}

// BeginLogin asks for a secure token and, with it, whatever the puzzle is
// this time.
//
// The puzzle does not come back attached to a failed login: a bare POST to
// Session answers 708 with no puzzle in it, which is the wrong end to start
// from. It comes from here.
//
// The password goes with it. That is not what anybody would design, and it is
// what the extension does - guessing differently from the client whose
// session type this is asking for is a long afternoon. It is the same
// password the next call sends anyway, one step earlier than it looks like it
// needs to be.
func BeginLogin(ctx context.Context, username, password string, timeout time.Duration) (
	token string, captcha *Captcha, err error) {

	body, err := ask(ctx, "AuthToken/login", nil,
		map[string]any{"username": username, "password": password}, timeout)
	if err != nil {
		var api *ApiError
		if errors.As(err, &api) && api.Code == rateLimited {
			return "", nil, &ApiError{
				Message: "Windscribe is rate-limiting this line. Wait a few " +
					"minutes before trying again - repeated attempts escalate.",
				Code: rateLimited,
			}
		}
		return "", nil, err
	}

	data := dataOf(body)
	token = str(data, "token")
	if token == "" {
		return "", nil, &ApiError{Message: "Windscribe returned no login token."}
	}

	raw, has := data["captcha"].(map[string]any)
	if !has || len(raw) == 0 {
		return token, nil, nil
	}
	if art := str(raw, "ascii_art"); art != "" {
		return token, &Captcha{Kind: "ascii", Art: art}, nil
	}
	top := 0
	if n, ok := raw["top"].(float64); ok {
		top = int(n)
	}
	return token, &Captcha{
		Kind:       "slider",
		Background: str(raw, "background"),
		Slider:     str(raw, "slider"),
		Top:        top,
	}, nil
}

// Session is what a login is worth keeping: the long-lived half.
type Session struct {
	Hash     string
	Username string
	Premium  bool
}

// FinishLogin is the login itself, with the solved puzzle attached.
//
// One attempt. A token is spent whether or not the answer was right, and
// asking again with the same one is how an account gets rate-limited for a
// mistake it already made.
func FinishLogin(
	ctx context.Context, username, password, token, solution string,
	trailX, trailY []int, code2fa string, timeout time.Duration,
) (Session, error) {

	sig := sha256.Sum256([]byte(token + signatureToken))
	payload := map[string]any{
		"username":         username,
		"password":         password,
		"session_type_id":  sessionTypeExtension,
		"secure_token":     token,
		"secure_token_sig": hex.EncodeToString(sig[:]),
		"captcha_solution": solution,
		// One object with two integer arrays, not a set of flattened
		// captcha_trail[x][0] form fields - that is the desktop client's
		// shape, and it belongs to the desktop's form encoding rather than to
		// what the API wants to receive.
		//
		// This is the half of the puzzle actually about being human: where
		// the piece stopped is easy, how a hand got there is not.
		"captcha_trail": map[string][]int{
			"x": ints(trailX), "y": ints(trailY),
		},
	}
	if code2fa != "" {
		payload["2fa_code"] = code2fa
	}

	body, err := ask(ctx, "Session", nil, payload, timeout)
	if err != nil {
		return Session{}, err
	}
	data := dataOf(body)
	hash := str(data, "session_auth_hash")
	if hash == "" {
		return Session{}, &ApiError{
			Message: "Windscribe accepted the login but returned no session."}
	}
	name := str(data, "username")
	if name == "" {
		name = username
	}
	premium := false
	if n, ok := data["is_premium"].(float64); ok {
		premium = n != 0
	}
	return Session{Hash: hash, Username: name, Premium: premium}, nil
}

func ints(v []int) []int {
	if v == nil {
		return []int{}
	}
	return v
}

// ProxyCredentials is the credential the proxy actually takes.
//
// No puzzle on this one, which is the whole reason the session is worth
// keeping: this is the call the app makes on its own, forever after the one
// login.
func ProxyCredentials(ctx context.Context, session string, timeout time.Duration) (
	user, password string, err error) {

	body, err := ask(ctx, "ServerCredentials",
		map[string]string{"session_auth_hash": session}, nil, timeout)
	if err != nil {
		return "", "", err
	}
	data := dataOf(body)
	rawUser, rawPass := str(data, "username"), str(data, "password")
	if rawUser == "" || rawPass == "" {
		return "", "", &ApiError{Message: "Windscribe returned no proxy credentials."}
	}
	// base64 in the JSON, both fields.
	u, err1 := base64.StdEncoding.DecodeString(rawUser)
	p, err2 := base64.StdEncoding.DecodeString(rawPass)
	if err1 != nil || err2 != nil {
		return "", "", &ApiError{Message: "Windscribe returned credentials that " +
			"were not base64."}
	}
	return string(u), string(p), nil
}

// -- the fleet ---------------------------------------------------------------

// WindscribeServer is one exit, flattened out of the published list.
type WindscribeServer struct {
	Country  string
	City     string
	Code     string
	Hostname string
	Nick     string
	Load     *int
	Gbps     int
	P2P      bool
	Premium  bool
}

var notLetters = regexp.MustCompile(`[^a-z]`)

// cityCodes is three letters per city, assigned over the whole list at once.
//
// Per entry would not do, and two things in the published list are why. The
// same city appears in several groups - Vienna twice, New York four times -
// and a second code for it would show as two cities in the catalogue with
// half the servers each. And one country arrives as several entries: the US
// is Central, East and West, all with country_code US, so codes handed out
// per entry would reset between them and two cities could take the same one.
//
// Sorted, so the same fleet produces the same filenames every time. A second
// run that renamed everything would pin a duplicate of every exit.
func cityCodes(pairs [][2]string) map[[2]string]string {
	sorted := append([][2]string(nil), pairs...)
	sort.Slice(sorted, func(i, j int) bool {
		if sorted[i][0] != sorted[j][0] {
			return sorted[i][0] < sorted[j][0]
		}
		return sorted[i][1] < sorted[j][1]
	})

	out := map[[2]string]string{}
	taken := map[string]map[string]bool{}
	for _, pair := range sorted {
		if _, done := out[pair]; done {
			continue
		}
		country, city := pair[0], pair[1]
		if taken[country] == nil {
			taken[country] = map[string]bool{}
		}
		letters := notLetters.ReplaceAllString(strings.ToLower(city), "")
		if letters == "" {
			letters = "xxx"
		}
		code := (letters + "xxx")[:3]
		if taken[country][code] {
			spare := ""
			if len(letters) > 3 {
				spare = letters[3:]
			}
			for _, c := range spare + "abcdefghijklmnopqrstuvwxyz0123456789" {
				alt := code[:2] + string(c)
				if !taken[country][alt] {
					code = alt
					break
				}
			}
		}
		taken[country][code] = true
		out[pair] = code
	}
	return out
}

// WindscribeServers is every exit Windscribe publishes, flattened.
//
// The list carries hostnames and no addresses, which is exactly the shape
// this repo is built for: resolving them honestly and pinning the result is
// machinery that already exists.
func WindscribeServers(ctx context.Context, timeout time.Duration) ([]WindscribeServer, error) {
	status, body, err := Fetch(ctx, "assets.windscribe.com", serverListURL, "GET",
		map[string]string{"Accept": "application/json"}, nil, timeout)
	if err != nil {
		return nil, fmt.Errorf("could not fetch the Windscribe server list: %w", err)
	}
	if status < 200 || status > 299 {
		return nil, fmt.Errorf("the Windscribe server list answered %d - if that "+
			"is a 403, their API is refusing this line: connect first, then "+
			"fetch", status)
	}

	var list struct {
		Data []struct {
			CountryCode string `json:"country_code"`
			P2P         int    `json:"p2p"`
			PremiumOnly int    `json:"premium_only"`
			Groups      []struct {
				City      string `json:"city"`
				Nick      string `json:"nick"`
				Health    *int   `json:"health"`
				LinkSpeed any    `json:"link_speed"`
				Hosts     []struct {
					Hostname string `json:"hostname"`
				} `json:"hosts"`
			} `json:"groups"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &list); err != nil {
		return nil, errors.New("the Windscribe server list did not parse")
	}

	type entry struct {
		country string
		city    string
		group   int
		at      int
	}
	var pairs [][2]string
	var entries []entry
	for i, country := range list.Data {
		code := strings.ToLower(country.CountryCode)
		if len(code) != 2 {
			continue
		}
		for g := range country.Groups {
			city := country.Groups[g].City
			if city == "" {
				city = country.Groups[g].Nick
			}
			if city == "" {
				city = "city"
			}
			pairs = append(pairs, [2]string{code, city})
			entries = append(entries, entry{code, city, g, i})
		}
	}
	codes := cityCodes(pairs)

	var out []WindscribeServer
	for _, e := range entries {
		country := list.Data[e.at]
		group := country.Groups[e.group]
		for _, host := range group.Hosts {
			if host.Hostname == "" {
				continue
			}
			gbps := 1
			if fmt.Sprint(group.LinkSpeed) == "10000" {
				gbps = 10
			}
			out = append(out, WindscribeServer{
				Country: e.country, City: e.city, Code: codes[[2]string{e.country, e.city}],
				Hostname: host.Hostname, Nick: group.Nick, Load: group.Health,
				Gbps: gbps,
				P2P:  country.P2P != 0,
				// The country's flag and not the group's. Every group in the
				// list carries pro=1, including the ones in the thirteen
				// countries a free account can reach, so reading the group
				// would mark the whole fleet premium and leave free_only with
				// nothing at all.
				Premium: country.PremiumOnly != 0,
			})
		}
	}
	return out, nil
}

// windscribeStub is the smallest thing a pin run will accept and read a name
// out of. Nothing here builds a tunnel - the app speaks to the proxy on 443
// directly - so a config is only ever somewhere to keep an address.
const windscribeStub = `# Windscribe exit, for pinning only - this app talks to the
# proxy on 443 and never runs OpenVPN against it.
client
dev tun
proto tcp
remote %s 443
`

// WriteWindscribeConfigs turns the published list into unpinned configs.
func WriteWindscribeConfigs(
	ctx context.Context, dest string, freeOnly bool, timeout time.Duration,
) (folder string, written, countries, total int, err error) {

	servers, err := WindscribeServers(ctx, timeout)
	if err != nil {
		return "", 0, 0, 0, err
	}
	if err := os.MkdirAll(dest, 0o755); err != nil {
		return "", 0, 0, 0, err
	}

	notes := map[string]WsNote{}
	seen := map[string]bool{}
	for _, s := range servers {
		if freeOnly && s.Premium {
			continue
		}
		total++
		seen[s.Country] = true

		name := s.Country + "-" + s.Code + windscribeMark + s.Hostname + ".ovpn"
		path := filepath.Join(dest, name)

		gbps := s.Gbps
		p2p := s.P2P
		premium := s.Premium
		notes[ConfigStem(name)] = WsNote{
			City: s.City, Nick: s.Nick, Load: s.Load, Gbps: &gbps,
			P2P: &p2p, Premium: &premium, Host: s.Hostname,
		}

		if _, statErr := os.Stat(path); statErr == nil {
			continue
		}
		stub := fmt.Sprintf(windscribeStub, s.Hostname)
		if writeErr := os.WriteFile(path, []byte(stub), 0o644); writeErr == nil {
			written++
		}
	}

	_ = writeJSON(WindscribeMetaPath(), notes)
	return dest, written, len(seen), total, nil
}
