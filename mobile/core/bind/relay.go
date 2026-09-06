// Package relay is the face core presents to Android and iOS.
//
// It exists because gomobile will not bind core directly, and that is a
// feature rather than an obstacle. gomobile carries basic types, []byte,
// string, error, and structs and interfaces declared in the bound package -
// and nothing else. No context.Context, no []Server, no time.Duration, no
// map. Anything richer has to be flattened here, in one file, where the shape
// of the bridge can be read in one sitting rather than inferred from whatever
// the tool happened to accept.
//
// So core stays idiomatic Go and this stays deliberately dull: milliseconds
// instead of durations, an Add method instead of a slice, an interface
// instead of a func, Stop instead of a cancel func.
package relay

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// Progress is what the phone implements to hear how a race is going. It is
// called from a worker goroutine, which on Android is not the main thread -
// an implementation that touches a View has to post the work itself.
type Progress interface {
	OnProgress(asked, total int)
}

// Result is the exit that won. A struct rather than several return values
// because gomobile gives a multi-value return an awkward shape in both
// languages, and this one is going straight into a list row.
type Result struct {
	Addr   string
	Name   string
	Path   string
	TookMs int
}

// Client holds the candidates and the credentials for one run.
//
// Built up by calling Add rather than handed a list, because a slice of
// structs is exactly what gomobile cannot carry across.
type Client struct {
	mu       sync.Mutex
	servers  []core.Server
	user     string
	password string
	cancel   context.CancelFunc
	tunnel   *core.Tunnel
	cat      *core.Catalogue

	// The multiplexed session, when the way out is the user's own server.
	// Held apart from the tunnel because it outlives a single connection and
	// has to be closed by hand.
	server *core.TunnelServer
}

var errTunnelUp = errors.New("a tunnel is already up - stop it before starting another")

func NewClient(user, password string) *Client {
	return &Client{user: user, password: password}
}

// Add offers one pinned exit. The name is what its certificate has to serve;
// an entry without one is refused at connect time rather than here, so that a
// bad config is reported against the config and not against the whole list.
func (c *Client) Add(addr, name, path string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.servers = append(c.servers, core.Server{Addr: addr, Name: name, Path: path})
}

// AddConfigFile reads a pinned .ovpn from disk and adds it. Returns the error
// rather than swallowing it, so a phone that copied a folder over can say
// which file it could not read.
func (c *Client) AddConfigFile(path string) error {
	s, err := core.ReadConfig(path)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.servers = append(c.servers, s)
	return nil
}

// ScanFolder reads a folder of pinned configs and becomes the candidate list.
//
// Replaces whatever was loaded rather than adding to it: the folder is the
// truth, and a second scan after files were pushed at the phone should show
// what is there now, not what has ever been there.
func (c *Client) ScanFolder(folder string) error {
	cat, err := core.Scan(folder)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cat = cat
	c.servers = cat.Servers()
	return nil
}

// CountriesJSON is the list the window renders, already encoded.
//
// A JSON string rather than a shape, because gomobile cannot carry a slice of
// structs at all and the page parses JSON regardless. One encode here, one
// parse there, and no fifteen-field mapping to keep in step through two
// languages by hand.
func (c *Client) CountriesJSON() string {
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return "[]"
	}
	return cat.CountriesJSON()
}

// RaceIn is Race narrowed to one country. "auto" and "" mean all of them,
// which is what the window's own default sends.
func (c *Client) RaceIn(country string, timeoutMs int, width int, p Progress) (*Result, error) {
	c.mu.Lock()
	cat := c.cat
	c.mu.Unlock()
	if cat == nil {
		return nil, errors.New("nothing has been scanned yet")
	}
	return c.race(cat.In(country), timeoutMs, width, p)
}

// Count is how many candidates are loaded.
func (c *Client) Count() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.servers)
}

// Reset empties the candidates, keeping the credentials.
func (c *Client) Reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.servers = nil
}

// Race asks the candidates and returns the first that says yes.
//
// Blocking, and meant to be: the phone calls it off its own background
// thread, and Stop is what interrupts it. Returning a channel or taking a
// callback for the result would both cross the bridge worse than this does.
func (c *Client) Race(timeoutMs int, width int, p Progress) (*Result, error) {
	c.mu.Lock()
	servers := append([]core.Server(nil), c.servers...)
	c.mu.Unlock()
	return c.race(servers, timeoutMs, width, p)
}

func (c *Client) race(servers []core.Server, timeoutMs int, width int, p Progress) (*Result, error) {
	c.mu.Lock()
	user, password := c.user, c.password
	c.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	c.mu.Lock()
	c.cancel = cancel
	c.mu.Unlock()
	defer cancel()

	var progress core.Progress
	if p != nil {
		progress = p.OnProgress
	}

	w, err := core.Race(ctx, servers, user, password,
		time.Duration(timeoutMs)*time.Millisecond, width, progress)
	if err != nil {
		return nil, err
	}
	return &Result{
		Addr:   w.Server.Addr,
		Name:   w.Server.Name,
		Path:   w.Server.Path,
		TookMs: int(w.Took.Milliseconds()),
	}, nil
}

// HostsJSON is what went where while this tunnel has been up, in the shape
// the log sheet reads.
//
// Pulled rather than pushed, the way the desktop does it: four numbers of
// meter belong on screen whenever a route is up, and a hundred rows belong
// there only while somebody is looking at them.
//
// An empty answer when nothing is carrying, rather than an error - the sheet
// can be opened before connecting, and "not live" is a state it already draws.
func (c *Client) HostsJSON() string {
	c.mu.Lock()
	t := c.tunnel
	c.mu.Unlock()
	if t == nil {
		return `{"live":false,"rows":[]}`
	}
	return t.HostsJSON()
}

// Stop ends a Race in flight. Safe to call when none is running.
func (c *Client) Stop() {
	c.mu.Lock()
	cancel := c.cancel
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// Ask is one exit on its own: whether it takes the credentials, in
// milliseconds. What a "test this server" row calls.
func (c *Client) Ask(addr, name string, timeoutMs int) (int, error) {
	took, err := core.Ask(core.NewExit(addr, name, c.user, c.password),
		time.Duration(timeoutMs)*time.Millisecond)
	if err != nil {
		return 0, err
	}
	return int(took.Milliseconds()), nil
}

// Resolve pins a name over DoH and returns the public addresses, one per
// line. A string rather than a slice because gomobile does not carry
// []string either, and the caller is going to split it once.
func Resolve(resolver, name string, timeoutMs int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(),
		time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	addrs, err := core.Resolve(ctx, nil, resolver, name)
	if err != nil {
		return "", err
	}
	out := ""
	for i, a := range addrs {
		if i > 0 {
			out += "\n"
		}
		out += a.String()
	}
	return out, nil
}

// -- carrying the traffic ---------------------------------------------------

// Namer is ConnectivityManager.getConnectionOwnerUid, seen from Go: which
// application opened a connection.
//
// The desktop looks up the owner of a local port to answer this. Android has
// the same question and a different API for it, and the answer goes in the
// same column.
type Namer interface {
	// Name is the application, or "" when the platform will not say.
	// Addresses are "ip:port". Called on the goroutine carrying the
	// connection, so it must be quick.
	Name(protocol int, source, destination string) string
}

// SetNamer hands over that lookup. Package-level and not per-Client for the
// same reason SetProtector is: there is one platform.
func SetNamer(n Namer) {
	if n == nil {
		core.SetNamer(nil)
		return
	}
	core.SetNamer(namerFunc(n.Name))
}

type namerFunc func(int, string, string) string

func (f namerFunc) Name(protocol int, source, destination string) string {
	return f(protocol, source, destination)
}

// Protector is VpnService, seen from Go. The phone implements it with one
// line: `override fun protect(fd: Long) = protect(fd.toInt())`.
type Protector interface {
	// Protect keeps one socket out of the tunnel. Returns false if Android
	// refused, which is fatal for that connection and worth saying so.
	//
	// int64 rather than int because gomobile maps Go's int to Java long, and
	// a signature that says long on one side and int on the other is the kind
	// of thing that compiles and then hands VpnService a truncated
	// descriptor.
	Protect(fd int64) bool
}

// SetProtector must be called before anything dials, and on Android it is not
// optional.
//
// Once VpnService routes 0.0.0.0/0 into the tun, the socket this app opens to
// the exit is routed there too: the exit's address goes into the tunnel,
// comes back out of the stack, is dialled again, and the phone spends a core
// talking to itself. Nothing errors. Nothing connects. This is the one call
// that prevents it.
func SetProtector(p Protector) {
	if p == nil {
		core.SetSocketProtector(nil)
		return
	}
	core.SetSocketProtector(func(fd int) bool { return p.Protect(int64(fd)) })
}

// StartTunnel begins carrying everything through one exit.
//
// fd is what VpnService.Builder.establish() returned. It stays the caller's:
// the tunnel duplicates it, and Android is the one that has to tear the
// interface down, so StopTunnel does not close it.
//
// Called after Race has chosen, with the winner's address and name - rather
// than taking a Result, because a Result that came back from a previous run
// and an exit that is still up are two different things and only the caller
// knows which it has.
func (c *Client) StartTunnel(fd int, mtu int, addr, name string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tunnel != nil {
		return errTunnelUp
	}
	t, err := core.StartTunnel(fd, mtu, core.NewExit(addr, name, c.user, c.password))
	if err != nil {
		return err
	}
	c.tunnel = t
	return nil
}

// StopTunnel ends it. Safe when nothing is running, because the phone's
// service lifecycle will call this from more than one place.
// StartServerTunnel carries the phone's traffic through the user's own server
// instead of through a provider's exit.
//
// The same tun, the same stack, the same DNS-over-HTTPS - only the thing
// underneath differs, and the stack is written not to know which. Which is
// why there is no second copy of any of that here.
func (c *Client) StartServerTunnel(fd int, mtu int, cfg *ServerConfig) error {
	if cfg == nil || cfg.Domain == "" {
		return errors.New("no server configured")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tunnel != nil {
		return errTunnelUp
	}

	srv := core.NewTunnelServer(cfg.Domain, cfg.Password, cfg.edges(),
		core.TunnelMode(cfg.Mode))
	t, err := core.StartTunnel(fd, mtu, srv)
	if err != nil {
		srv.Close()
		return err
	}
	c.tunnel = t
	c.server = srv
	return nil
}

func (c *Client) StopTunnel() error {
	c.mu.Lock()
	t, srv := c.tunnel, c.server
	c.tunnel, c.server = nil, nil
	c.mu.Unlock()

	// The session first, then the stack. A smux session left open holds a
	// websocket open, and a websocket left open holds the phone's radio awake
	// for as long as its keepalive keeps saying something.
	if srv != nil {
		srv.Close()
	}
	if t == nil {
		return nil
	}
	return t.Close()
}

// TunnelExit is the address traffic is leaving by, or "" when nothing is up.
// What a status line reads.
func (c *Client) TunnelExit() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tunnel == nil {
		return ""
	}
	return c.tunnel.Exit()
}

// TunAddress and TunMTU are what the tun has to be built with. Read by the
// Kotlin that configures VpnService.Builder, so that the two ends agree by
// construction rather than by two people remembering the same number.
func TunAddress() string { return core.TunAddress.Addr().String() }
func TunPrefix() int     { return core.TunAddress.Bits() }
func TunMTU() int        { return core.TunMTU }

// TunDNS is the address to hand VpnService as the DNS server. Any address
// inside the tun will do - every query is hijacked before it is routed and
// re-asked over HTTPS through the exit - but it has to be one, or Android
// leaves the phone's own resolver in place and the first lookup leaks.
func TunDNS() string { return "1.1.1.1" }

// SeenAsJSON is where the internet says the traffic came out, asked through
// the exit rather than about it.
//
// An exit's proxy address and its egress address are different numbers -
// 172.216.14.101 is dialled, 172.216.14.102 is what a website sees - so this
// has to go through the tunnel to be worth showing. Blocking, and slow: one
// CONNECT and a TLS handshake to another continent.
func SeenAsJSON(addr, name, user, password string, timeoutMs int) string {
	return core.SeenAsJSON(addr, name, user, password,
		time.Duration(timeoutMs)*time.Millisecond)
}

// WhereAmIJSON is the address this phone comes out at, asked directly rather
// than through the tunnel - the "before" of the two the window shows.
//
// Blocking, and slow enough to matter: the caller runs it off the main thread
// and pushes the answer at the page when it arrives, which is what the page's
// onRealIp is shaped for.
func WhereAmIJSON(timeoutMs int) string {
	return core.WhereAmIJSON(time.Duration(timeoutMs) * time.Millisecond)
}

// Version is here so a phone build can prove which core it is carrying
// without a debugger.
func Version() string { return "relay-core 0.1" }
