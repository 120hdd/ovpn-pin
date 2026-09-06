package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"time"
)

// Resolvers answer the same JSON shape, which is why both are here and either
// will do. Cloudflare first because it is the one the desktop pins with, so a
// phone and a desktop pinning the same name land on the same address.
//
// Addresses rather than names, and this was measured rather than tidied.
// Asked for cloudflare-dns.com, this line answers 10.10.34.35 - the same
// forged address the README opens with, aimed at the resolver that exists to
// escape it. The lookup that is supposed to be trustworthy cannot itself
// begin with an untrustworthy lookup, so the resolvers are pinned exactly as
// the exits are.
//
// It costs nothing extra to verify: for an address literal Go sends no SNI at
// all, and Cloudflare's certificate carries 1.1.1.1 as an IP SAN, so the
// connection is both unannounced and proved without a line of code here. The
// names are kept last as a fallback for a line that is not doing this.
//
// Only the first of these answered from Tehran when this was written -
// 1.0.0.1, 8.8.8.8 and dns.google all timed out - which is the reason for a
// list rather than a constant.
var Resolvers = map[string][]string{
	"cloudflare": {
		"https://1.1.1.1/dns-query",
		"https://1.0.0.1/dns-query",
		"https://cloudflare-dns.com/dns-query",
	},
	"google": {
		"https://8.8.8.8/resolve",
		"https://8.8.4.4/resolve",
		"https://dns.google/resolve",
	},
}

// Everything a public hostname has no business resolving to. Rejecting the
// whole of RFC1918 and friends rather than a list of known-forged addresses
// means this keeps working when the censor picks a different one tomorrow -
// 10.10.34.35 is the one this repo was written against, and it is not going
// to be the last.
var reserved = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("127.0.0.0/8"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("224.0.0.0/4"),
}

// IsPublic is whether an address is one a real exit could be at.
func IsPublic(ip netip.Addr) bool {
	if !ip.Is4() {
		return false
	}
	for _, p := range reserved {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

// ErrForged is every answer having been a reserved address. Told apart from
// an empty answer on purpose: a name that does not exist any more is the
// provider retiring a server, and a name that resolves to a machine on your
// own LAN is the resolver lying. They want completely different things doing
// about them and the difference is invisible in a bare "lookup failed".
var ErrForged = errors.New("every address returned was a reserved one - the resolver is answering for someone else")

type dohAnswer struct {
	Status int `json:"Status"`
	Answer []struct {
		Type int    `json:"type"`
		Data string `json:"data"`
	} `json:"Answer"`
}

// Resolve asks a DoH resolver for the A records of a name and returns only
// the public ones, in the order they were given.
//
// The client is a parameter rather than package state because on a phone this
// is the one lookup that may have to go through a proxy the app is already
// holding: bootstrapping a new exit while connected to an old one. Passing
// nil asks directly.
func Resolve(ctx context.Context, client *http.Client, resolver, name string) ([]netip.Addr, error) {
	endpoints, ok := Resolvers[resolver]
	if !ok {
		return nil, fmt.Errorf("no resolver called %q", resolver)
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}

	// Down the list until one of them answers. A blocked endpoint is a dead
	// socket rather than an error page, so the cost of trying the next is a
	// timeout - which is why the one that works on this line is first.
	var last error
	for _, endpoint := range endpoints {
		addrs, err := resolveAt(ctx, client, resolver, endpoint, name)
		if err == nil {
			return addrs, nil
		}
		// A resolver that answered and said no is an answer. Trying the next
		// one would turn "this name is retired" into "the network is down".
		if errors.Is(err, ErrForged) || isAnswer(err) {
			return nil, err
		}
		last = err
	}
	return nil, last
}

// answered marks the errors that came back from a resolver that was reached,
// as opposed to one that could not be.
type answered struct{ error }

func isAnswer(err error) bool {
	var a answered
	return errors.As(err, &a)
}

func resolveAt(ctx context.Context, client *http.Client, resolver, endpoint, name string) ([]netip.Addr, error) {
	q := endpoint + "?name=" + url.QueryEscape(name) + "&type=A"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, q, nil)
	if err != nil {
		return nil, err
	}
	// Both of these matter. Without the Accept header Cloudflare answers with
	// the wire format rather than JSON, and an agent string keeps the request
	// looking like the tool it is.
	req.Header.Set("Accept", "application/dns-json")
	req.Header.Set("User-Agent", "ovpn-pin")

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s answered %s", resolver, resp.Status)
	}

	var out dohAnswer
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("%s answered something that is not DNS JSON: %w", resolver, err)
	}
	// 3 is NXDOMAIN: the name is gone, which is a provider retiring a server
	// rather than anything being wrong here.
	if out.Status == 3 {
		return nil, answered{fmt.Errorf("%s says %s does not exist", resolver, name)}
	}
	if out.Status != 0 {
		return nil, fmt.Errorf("%s answered with rcode %d", resolver, out.Status)
	}

	var addrs []netip.Addr
	seen := map[netip.Addr]bool{}
	forged := 0
	for _, a := range out.Answer {
		// An A query can be answered with CNAMEs too. Type 1 is A; anything
		// else in here is a step along the way rather than an address.
		if a.Type != 1 {
			continue
		}
		ip, err := netip.ParseAddr(a.Data)
		if err != nil || !ip.Is4() {
			continue
		}
		if !IsPublic(ip) {
			forged++
			continue
		}
		if seen[ip] {
			continue
		}
		seen[ip] = true
		addrs = append(addrs, ip)
	}
	if len(addrs) == 0 && forged > 0 {
		return nil, ErrForged
	}
	if len(addrs) == 0 {
		return nil, answered{fmt.Errorf("%s returned no addresses for %s", resolver, name)}
	}
	return addrs, nil
}

// ProxyClient is an http.Client that goes through a local proxy - the one
// this app is already serving, when re-pinning while connected.
func ProxyClient(addr string, timeout time.Duration) (*http.Client, error) {
	u, err := url.Parse("http://" + addr)
	if err != nil {
		return nil, err
	}
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy:               http.ProxyURL(u),
			DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
			TLSHandshakeTimeout: timeout,
		},
	}, nil
}
