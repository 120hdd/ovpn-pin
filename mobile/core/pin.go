package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Turning a name into an address, honestly, and writing that down.
//
// The whole repo is named after this. A config that says `remote
// fr-030.totallyacdn.com` is a config that asks this line to resolve a name
// it is lying about; a pinned copy says `remote 146.70.253.194` and carries
// the name in a comment, so the certificate can still be checked against it
// and nothing has to be looked up at connect time.
//
// The desktop shells out to a PowerShell script for this, because that script
// is the thing that was measured. A phone has no PowerShell, and the parts
// that matter - a pinned DoH resolver and a list of reserved prefixes to
// throw away - are already in this package.

// PinProgress is how a run says where it has got to, one JSON object per
// event, in the shape window.onPin reads.
type PinProgress interface {
	OnPin(payload string)
}

// PinOptions is what a run is asked for.
type PinOptions struct {
	Inbox string // the folder of unpinned configs
	Out   string // where pinned copies land

	// "direct" or "proxy". Proxy means through whatever is carrying traffic
	// now, which is the only sense the word has on a phone - there is no
	// local port here for anything to listen on.
	Route string

	// How many addresses to keep per config. A name resolving to eight and
	// only the first being pinned is seven exits thrown away; pinning all
	// eight is eight files for one server.
	MaxIPs int

	// Whether to see if the address answers before writing it. Off makes a
	// run about three times quicker and the result about that much less
	// worth having.
	Test bool
}

func NewPinOptions() *PinOptions {
	return &PinOptions{Route: "direct", MaxIPs: 4, Test: true}
}

const maxIPsCeiling = 32

func (o *PinOptions) tidy() {
	if o.Inbox == "" {
		o.Inbox = ConfigsDir()
	}
	if o.Out == "" {
		o.Out = PinnedDir()
	}
	if o.Route != "proxy" {
		o.Route = "direct"
	}
	if o.MaxIPs < 1 {
		o.MaxIPs = 1
	}
	if o.MaxIPs > maxIPsCeiling {
		o.MaxIPs = maxIPsCeiling
	}
}

// A run at a time, and a way to stop it.
var (
	pinMu    sync.Mutex
	pinStop  chan struct{}
	pinState atomic.Value // string: idle, running, done
)

func pinPhase() string {
	if s, ok := pinState.Load().(string); ok && s != "" {
		return s
	}
	return "idle"
}

type blocker struct {
	Kind string `json:"kind"`
	Say  string `json:"say"`
}

// pinBlockers is what would stop a run, and nothing else. Checked before a
// button is pressed rather than reported after it.
func pinBlockers(o *PinOptions, waiting int) []blocker {
	var out []blocker
	if waiting == 0 {
		out = append(out, blocker{
			Kind: "no-configs",
			Say: "Nothing waiting in " + o.Inbox + ". Get a server list first, " +
				"or import a folder of configs.",
		})
	}
	if o.Route == "proxy" && LiveWay() == nil {
		out = append(out, blocker{
			Kind: "no-proxy",
			Say: "Nothing is carrying traffic yet. Connect first, or resolve " +
				"directly.",
		})
	}
	return out
}

// pinMinutes is roughly how long a run will take: a lookup and a handshake
// per config, four at a time, and a little more per address kept. Rounded up,
// and never zero for a run that has something to do - "0 minutes" reads as a
// button that will not work.
func pinMinutes(waiting, maxIPs int) int {
	if waiting == 0 {
		return 0
	}
	seconds := waiting * 16 / 10 * (8 + maxIPs) / 8
	minutes := (seconds + 59) / 60
	if minutes < 1 {
		return 1
	}
	return minutes
}

// countOvpn is how many .ovpn files a folder holds.
func countOvpn(folder string) int {
	entries, err := os.ReadDir(folder)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".ovpn") {
			n++
		}
	}
	return n
}

// PinPlanJSON is what a run would cost and what would stop it.
func PinPlanJSON(o *PinOptions, quick bool) string {
	o.tidy()
	waiting := countOvpn(o.Inbox)
	existing := countOvpn(o.Out)

	// A ceiling rather than a promise: most names resolve to fewer than
	// MaxIPs, and some resolve to nothing at all.
	most := waiting * o.MaxIPs
	minutes := pinMinutes(waiting, o.MaxIPs)

	b, err := json.Marshal(struct {
		Folder        string    `json:"folder"`
		Out           string    `json:"out"`
		Total         int       `json:"total"`
		Existing      int       `json:"existing"`
		Route         string    `json:"route"`
		Port          int       `json:"port"`
		MaxIPs        int       `json:"maxIps"`
		Most          int       `json:"most"`
		Minutes       int       `json:"minutes"`
		Checked       bool      `json:"checked"`
		Blockers      []blocker `json:"blockers"`
		State         string    `json:"state"`
		Test          bool      `json:"test"`
		OutIsStandard bool      `json:"outIsStandard"`
	}{
		Folder: o.Inbox, Out: o.Out, Total: waiting, Existing: existing,
		Route: o.Route,
		// There is no port to choose on a phone: nothing listens, and a
		// tunnel is not something with an address. Said as zero rather than
		// omitted so the page draws the field empty rather than defaulting it.
		Port:   0,
		MaxIPs: o.MaxIPs, Most: most, Minutes: minutes,
		// The desktop uses this to say whether it actually probed the local
		// proxy. There is nothing to probe here, so nothing is being claimed.
		Checked:  false,
		Blockers: pinBlockers(o, waiting),
		State:    pinPhase(),
		Test:     o.Test,
		// The phone reads one folder, and this is it, so a run always lands
		// where the list is - which is what stops the page offering a button
		// to narrow onto a folder that is already the whole world.
		OutIsStandard: true,
	})
	if err != nil {
		return `{"total":0,"blockers":[]}`
	}
	return string(b)
}

type pinEvent struct {
	Phase string `json:"phase"`

	Total int    `json:"total"`
	Done  int    `json:"done"`
	Out   string `json:"out,omitempty"`
	Route string `json:"route,omitempty"`
	Port  int    `json:"port,omitempty"`
	Via   string `json:"via,omitempty"`

	Host      string `json:"host,omitempty"`
	Addresses string `json:"addresses,omitempty"`

	Source  string `json:"source,omitempty"`
	Name    string `json:"name,omitempty"`
	Outcome string `json:"outcome,omitempty"`
	IP      string `json:"ip,omitempty"`
	Detail  string `json:"detail,omitempty"`

	Read        int    `json:"read,omitempty"`
	Written     int    `json:"written,omitempty"`
	Reachable   int    `json:"reachable,omitempty"`
	Unreachable int    `json:"unreachable,omitempty"`
	Skipped     int    `json:"skipped,omitempty"`
	InFolder    int    `json:"inFolder,omitempty"`
	Cancelled   bool   `json:"cancelled,omitempty"`
	Error       string `json:"error,omitempty"`
}

func (e pinEvent) json() string {
	b, err := json.Marshal(e)
	if err != nil {
		return `{"phase":"finished"}`
	}
	return string(b)
}

// StartPin begins a run and returns at once. Everything else arrives as
// events, because the page draws each result on its own row as it lands.
func StartPin(o *PinOptions, p PinProgress) string {
	o.tidy()
	waiting := countOvpn(o.Inbox)
	if stops := pinBlockers(o, waiting); len(stops) > 0 {
		b, _ := json.Marshal(struct {
			OK       bool      `json:"ok"`
			Error    string    `json:"error"`
			Blockers []blocker `json:"blockers"`
		}{false, stops[0].Say, stops})
		return string(b)
	}

	pinMu.Lock()
	if pinStop != nil {
		pinMu.Unlock()
		return `{"ok":false,"error":"A run is already going."}`
	}
	stop := make(chan struct{})
	pinStop = stop
	pinMu.Unlock()

	pinState.Store("running")
	go pinRun(*o, p, stop)

	b, _ := json.Marshal(struct {
		OK      bool   `json:"ok"`
		Total   int    `json:"total"`
		Out     string `json:"out"`
		Minutes int    `json:"minutes"`
	}{true, waiting, o.Out, pinMinutes(waiting, o.MaxIPs)})
	return string(b)
}

// CancelPin stops a run in flight.
func CancelPin() string {
	pinMu.Lock()
	defer pinMu.Unlock()
	if pinStop == nil {
		return `{"ok":false}`
	}
	close(pinStop)
	pinStop = nil
	return `{"ok":true}`
}

func pinRun(o PinOptions, p PinProgress, stop chan struct{}) {
	say := func(e pinEvent) {
		if p != nil {
			p.OnPin(e.json())
		}
	}
	stopped := func() bool {
		select {
		case <-stop:
			return true
		default:
			return false
		}
	}
	defer func() {
		// A panic in here would take the whole application down with it and
		// say nothing. See the comment on guard in the bind package.
		if r := recover(); r != nil {
			say(pinEvent{Phase: "finished", Error: fmt.Sprint(r), Out: o.Out})
		}
		pinMu.Lock()
		if pinStop == stop {
			pinStop = nil
		}
		pinMu.Unlock()
		pinState.Store("done")
	}()

	_ = os.MkdirAll(o.Out, 0o755)

	sources := unpinned(o.Inbox)
	say(pinEvent{Phase: "starting", Total: len(sources), Out: o.Out,
		Route: o.Route, Port: 0})

	via := "direct"
	client := direct(20 * time.Second)
	if o.Route == "proxy" {
		if w := LiveWay(); w != nil {
			client = through(w, 20*time.Second)
			via = "the tunnel"
		}
	}
	say(pinEvent{Phase: "route", Via: via})
	say(pinEvent{Phase: "resolving", Total: len(sources), Route: o.Route})

	var (
		mu          sync.Mutex
		seen        = map[string]bool{}
		written     int
		reachable   int
		unreachable int
		skipped     int
	)

	// One lookup per distinct name, kept, because a folder can hold several
	// configs for the same host and the answer will not have changed between
	// two of them a second apart.
	var lookupMu sync.Mutex
	looked := map[string][]string{}
	failed := map[string]error{}

	gate := make(chan struct{}, 4)
	var wg sync.WaitGroup

	for _, name := range sources {
		if stopped() {
			break
		}
		wg.Add(1)
		go func(name string) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			if stopped() {
				return
			}

			done := func(e pinEvent) {
				mu.Lock()
				seen[name] = true
				e.Done = len(seen)
				e.Total = len(sources)
				e.Source = name
				mu.Unlock()
				say(e)
			}

			text, err := os.ReadFile(filepath.Join(o.Inbox, name))
			if err != nil {
				mu.Lock()
				skipped++
				mu.Unlock()
				done(pinEvent{Phase: "result", Outcome: "skipped", Name: name,
					Detail: err.Error()})
				return
			}

			host, port, found := firstRemote(string(text))
			if !found {
				mu.Lock()
				skipped++
				mu.Unlock()
				done(pinEvent{Phase: "result", Outcome: "skipped", Name: name,
					Detail: "no remote line in it"})
				return
			}
			if dottedQuad.MatchString(host) {
				mu.Lock()
				skipped++
				mu.Unlock()
				done(pinEvent{Phase: "result", Outcome: "skipped", Name: name,
					Detail: "already an address"})
				return
			}

			lookupMu.Lock()
			addrs, asked := looked[host]
			lookErr := failed[host]
			lookupMu.Unlock()
			if !asked && lookErr == nil {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				got, err := Resolve(ctx, client, "cloudflare", host)
				cancel()
				lookupMu.Lock()
				if err != nil {
					failed[host] = err
					lookErr = err
				} else {
					for _, a := range got {
						addrs = append(addrs, a.String())
					}
					looked[host] = addrs
				}
				lookupMu.Unlock()
			}

			if lookErr != nil {
				mu.Lock()
				skipped++
				mu.Unlock()
				// A forged answer is worth naming separately: it is the line
				// lying about this name rather than the name being retired,
				// and it is the thing this whole repo exists because of.
				if errors.Is(lookErr, ErrForged) {
					say(pinEvent{Phase: "forged", Host: host,
						Addresses: "a reserved address"})
				}
				done(pinEvent{Phase: "result", Outcome: "skipped", Name: name,
					Detail: why(lookErr)})
				return
			}

			keep := addrs
			if len(keep) > o.MaxIPs {
				keep = keep[:o.MaxIPs]
			}
			stem := strings.TrimSuffix(name, ".ovpn")
			for _, ip := range keep {
				if stopped() {
					return
				}
				out := stem + "_" + ip + ".ovpn"
				path := filepath.Join(o.Out, out)
				if _, err := os.Stat(path); err == nil {
					mu.Lock()
					skipped++
					mu.Unlock()
					done(pinEvent{Phase: "result", Outcome: "skipped",
						Name: out, IP: ip, Detail: "already pinned"})
					continue
				}

				outcome := "written"
				if o.Test {
					conn, err := (&net.Dialer{Timeout: 4 * time.Second}).Dial(
						"tcp", net.JoinHostPort(ip, port))
					if err == nil {
						conn.Close()
						outcome = "reachable"
					} else {
						outcome = "unreachable"
					}
				}

				if err := os.WriteFile(path, []byte(pinnedText(string(text), host, ip)), 0o644); err != nil {
					mu.Lock()
					skipped++
					mu.Unlock()
					done(pinEvent{Phase: "result", Outcome: "skipped",
						Name: out, IP: ip, Detail: err.Error()})
					continue
				}

				mu.Lock()
				written++
				switch outcome {
				case "reachable":
					reachable++
				case "unreachable":
					unreachable++
				}
				mu.Unlock()
				done(pinEvent{Phase: "result", Outcome: outcome, Name: out, IP: ip})
			}
		}(name)
	}
	wg.Wait()

	mu.Lock()
	read := len(seen)
	mu.Unlock()

	say(pinEvent{
		Phase: "finished", Read: read, Total: len(sources), Written: written,
		Reachable: reachable, Unreachable: unreachable, Skipped: skipped,
		Out: o.Out, InFolder: countOvpn(o.Out), Route: via,
		Cancelled: stopped(),
	})
}

// unpinned is every config in a folder that still names a host.
func unpinned(folder string) []string {
	entries, err := os.ReadDir(folder)
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".ovpn") {
			out = append(out, e.Name())
		}
	}
	sort.Strings(out)
	return out
}

// firstRemote is the host and port of the first remote line.
func firstRemote(text string) (host, port string, found bool) {
	at := remoteAnywhere.FindStringSubmatchIndex(text)
	if at == nil {
		return "", "", false
	}
	host = text[at[4]:at[5]]
	port = "443"
	if fields := strings.Fields(text[at[6]:at[7]]); len(fields) > 0 {
		port = fields[0]
	}
	return host, port, true
}

// pinnedText is the config with its remote replaced and the name it was
// pinned from written above it.
//
// That comment is the whole point. Without it the certificate has nothing to
// be checked against, and core/config.go refuses the file rather than dialling
// an address it cannot prove anything about. The header is the desktop's, so
// a config pinned on a phone reads the same as one pinned on a desktop.
func pinnedText(text, host, ip string) string {
	head := fmt.Sprintf(
		"# pinned by Relay on %s - cloudflare over DoH\n#   %s -> %s\n",
		time.Now().Format("2006-01-02 15:04"), host, ip)
	return head + swapRemote(text, ip)
}
