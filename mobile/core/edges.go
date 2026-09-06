package core

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Which Cloudflare addresses will carry the domain from this line.
//
// The same problem the rest of this repo is about, one layer up. The exits
// are pinned because the resolver lies about their names; the edges are
// measured because most of Cloudflare's address space is filtered here and
// the few that are not are not the ones DNS hands out. Neither is a fact
// about the server - both are facts about the line, and both go stale.
//
// A straight port of edge_scan/edge_probe in core/ovpn-proxy.py. The blocks
// and the probe have to agree with the desktop's: an address the desktop
// found live is one this should find live, or the two halves of the same
// account disagree about whether the server is up.

// cfEdgeBlocks are CF_EDGE_BLOCKS. Prefixes rather than whole addresses,
// because the host part is filled in below.
var cfEdgeBlocks = []string{
	"104.16", "104.17", "104.18", "104.19", "104.20", "104.21",
	"104.22", "104.23", "104.24", "104.25", "104.26", "104.27",
	"172.64", "172.65", "172.66", "172.67", "172.68", "172.69",
	"172.70", "172.71",
	"188.114.96", "188.114.97", "188.114.98", "188.114.99",
	"162.159.135", "162.159.140", "108.162.192", "141.101.90",
}

// cfEdgeHosts are CF_EDGE_HOSTS: the tail of each candidate address.
var cfEdgeHosts = []string{"10.10", "60.60"}

// EdgeKeep is how many go into the client.
//
// More than one because the first can die mid-session and the client should
// step over it rather than stop; not many more because every extra one is
// another address to step over on the way to a live one, at about half a
// second each.
const EdgeKeep = 6

// EdgeCandidates is every address worth asking.
func EdgeCandidates() []string {
	var out []string
	for _, block := range cfEdgeBlocks {
		for _, host := range cfEdgeHosts {
			tail := host
			if strings.Count(block, ".") != 1 {
				tail = strings.SplitN(host, ".", 2)[0]
			}
			out = append(out, block+"."+tail)
		}
	}
	return out
}

// EdgeVerdict is what to do about one address.
type EdgeVerdict string

const (
	EdgeOK      EdgeVerdict = "ok"      // use it
	EdgeBlocked EdgeVerdict = "blocked" // never answered - the case this exists for
	EdgeForeign EdgeVerdict = "foreign" // reachable, but not carrying this zone
	EdgeOrigin  EdgeVerdict = "origin"  // the edge is fine, the server behind it is not
	EdgeOdd     EdgeVerdict = "odd"     // answered with none of the above
)

// EdgeProbe is what one address is worth, asked without a password.
//
// `/api/config` answers 401 to a request carrying no credentials, and that
// 401 is the whole test: it says the address is reachable, that the name on
// the certificate matches, that Cloudflare recognises the zone, and that what
// stands behind it is our nginx and our gost rather than somebody else's
// site. Nothing secret is sent to find that out, which is what makes it safe
// to fire at several dozen strangers' addresses at once.
func EdgeProbe(ctx context.Context, domain, ip string, timeout time.Duration) (EdgeVerdict, time.Duration) {
	started := time.Now()

	raw, err := (&net.Dialer{Timeout: timeout}).DialContext(
		ctx, "tcp", net.JoinHostPort(ip, "443"))
	if err != nil {
		return EdgeBlocked, time.Since(started)
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(timeout))

	conn := tls.Client(raw, &tls.Config{ServerName: domain})
	if err := conn.Handshake(); err != nil {
		return EdgeBlocked, time.Since(started)
	}

	req := "GET /api/config HTTP/1.1\r\n" +
		"Host: " + domain + "\r\n" +
		"User-Agent: Relay (ovpn-pin)\r\n" +
		"Connection: close\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		return EdgeBlocked, time.Since(started)
	}

	buf := make([]byte, 4096)
	n, _ := conn.Read(buf)
	took := time.Since(started)
	if n == 0 {
		return EdgeBlocked, took
	}
	// The status line, read as a number rather than matched as text. The
	// same rules edge_probe applies, and they have to stay the same rules:
	// an address the desktop calls live and this one calls foreign is one
	// half of an account disagreeing with the other about whether the server
	// is up.
	fields := strings.SplitN(string(buf[:n]), " ", 3)
	if len(fields) < 2 {
		return EdgeOdd, took
	}
	code, err := strconv.Atoi(fields[1])
	if err != nil {
		return EdgeOdd, took
	}

	switch {
	case code == 200 || code == 401:
		return EdgeOK, took
	// Cloudflare's own numbers for a server it cannot reach. They arrive
	// through a perfectly good edge address, which is exactly why they are
	// worth telling apart from one that is blocked: scanning for another
	// address would never end, and would never have been the problem.
	case code == 502 || (code >= 520 && code <= 526):
		return EdgeOrigin, took
	case code >= 400 && code < 500:
		return EdgeForeign, took
	}
	return EdgeOdd, took
}

// EdgeScan asks every candidate and returns the live ones, quickest first,
// with a tally of what the rest said.
//
// Everything is asked rather than stopping at the first that answers. The
// whole set costs a few seconds at this width - the blocked ones are what it
// waits for, and they are the majority on a bad day. The tally is what tells
// a filtered line apart from a dead server, and the times are what make the
// choice a choice rather than whichever goroutine won.
func EdgeScan(ctx context.Context, domain string, timeout time.Duration, width int) ([]string, map[EdgeVerdict]int) {
	if width <= 0 {
		width = 28
	}

	type answer struct {
		ip      string
		verdict EdgeVerdict
		took    time.Duration
	}

	candidates := EdgeCandidates()
	answers := make([]answer, len(candidates))

	gate := make(chan struct{}, width)
	var wg sync.WaitGroup
	for i, ip := range candidates {
		wg.Add(1)
		go func(i int, ip string) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			v, took := EdgeProbe(ctx, domain, ip, timeout)
			answers[i] = answer{ip, v, took}
		}(i, ip)
	}
	wg.Wait()

	tally := map[EdgeVerdict]int{}
	var live []answer
	for _, a := range answers {
		tally[a.verdict]++
		if a.verdict == EdgeOK {
			live = append(live, a)
		}
	}
	sort.Slice(live, func(i, j int) bool { return live[i].took < live[j].took })

	out := make([]string, 0, len(live))
	for _, a := range live {
		out = append(out, a.ip)
	}
	return out, tally
}

// EdgesFor is EdgeScan cut to the number that goes into the client.
func EdgesFor(ctx context.Context, domain string, timeout time.Duration) ([]string, string, error) {
	live, tally := EdgeScan(ctx, domain, timeout, 0)
	if len(live) > EdgeKeep {
		live = live[:EdgeKeep]
	}

	// The tally, said out loud, because "no edges" has four different causes
	// and only one of them is anything to do with the server.
	said := fmt.Sprintf("%d ok, %d blocked, %d foreign, %d origin, %d odd",
		tally[EdgeOK], tally[EdgeBlocked], tally[EdgeForeign],
		tally[EdgeOrigin], tally[EdgeOdd])

	if len(live) == 0 {
		switch {
		case tally[EdgeOrigin] > 0:
			return nil, said, fmt.Errorf(
				"the edges answer but the server behind them does not (%s)", said)
		case tally[EdgeBlocked] == len(EdgeCandidates()):
			return nil, said, fmt.Errorf(
				"every Cloudflare address is filtered on this line (%s)", said)
		default:
			return nil, said, fmt.Errorf("no address carried %s (%s)", domain, said)
		}
	}
	return live, said, nil
}
