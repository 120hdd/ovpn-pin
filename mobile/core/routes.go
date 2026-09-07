package core

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

// Three ways to reach a provider's API from a line that is filtering it.
//
// The desktop tries the same three, in the same order, and the order is the
// point: through whatever is already carrying traffic, then straight at it,
// then at an address found over DNS-over-HTTPS with the name still on the
// handshake.
//
// That last one is the opposite of what exit.go does, and deliberately.
// exit.go goes to great trouble to prove a name without ever sending it,
// because announcing *.prod.surfshark.com gets the handshake killed on the
// way out. Here the name has to be sent: a CDN routes on SNI and will hand
// back somebody else's certificate without it. Two opposite decisions, one
// package, each right for its own reason.

// live is whatever is carrying traffic right now, or nothing.
//
// Set when a tunnel opens and cleared when it closes, so that a fetch which
// happens to run while the VPN is up goes through it. On Android that is not
// automatic: the app excludes itself from its own tunnel, which is right for
// the socket that dials the exit and wrong for everything else.
var live atomic.Value // Way

// SetLive says what is carrying traffic now. Called when a tunnel opens and
// again with nil when it closes.
func SetLive(w Way) {
	if w == nil {
		live.Store((*Exit)(nil))
		return
	}
	live.Store(w)
}

// LiveWay is what is carrying, or nil.
func LiveWay() Way {
	w, ok := live.Load().(Way)
	if !ok || w == nil {
		return nil
	}
	// A typed nil stored above reads as a non-nil interface here, which is
	// the one Go footgun this pattern always steps on.
	if e, isExit := w.(*Exit); isExit && e == nil {
		return nil
	}
	return w
}

// Route is one way of asking, and a name for it that can go in a log line.
type Route struct {
	How    string
	Client *http.Client
}

// through builds a client that dials every address through a Way.
func through(w Way, timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(_ context.Context, _, addr string) (net.Conn, error) {
				conn, spare, err := w.Open(addr, timeout)
				if err != nil {
					return nil, err
				}
				if len(spare) > 0 {
					conn.Close()
					return nil, errors.New("the exit spoke before it was asked to")
				}
				return conn, nil
			},
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		},
	}
}

// direct is an ordinary client that ignores any system proxy.
func direct(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: (&net.Dialer{
				Timeout: timeout, Control: dialControl,
			}).DialContext,
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		},
	}
}

// at dials one address while still calling the site by its name.
//
// The certificate is checked against the hostname and the handshake announces
// it, so this is an ordinary HTTPS request that has simply been told where to
// go. What it works around is a resolver that lies, not a certificate that
// does not match.
func at(host, address string, timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				_, port, err := net.SplitHostPort(addr)
				if err != nil {
					port = "443"
				}
				d := &net.Dialer{Timeout: timeout, Control: dialControl}
				return d.DialContext(ctx, network, net.JoinHostPort(address, port))
			},
			TLSClientConfig:     &tls.Config{ServerName: host},
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		},
	}
}

// Routes is every way worth trying to reach one host, in order.
//
// The DoH lookup that finds the last of them is itself pinned - see doh.go.
// Asked for cloudflare-dns.com by name, this line answers 10.10.34.35, which
// is the address the top-level README opens with, aimed at the resolver that
// exists to escape it.
func Routes(ctx context.Context, host string, timeout time.Duration) []Route {
	var out []Route

	if w := LiveWay(); w != nil {
		out = append(out, Route{How: "tunnel", Client: through(w, timeout)})
	}
	out = append(out, Route{How: "direct", Client: direct(timeout)})

	addrs, err := Resolve(ctx, direct(timeout), "cloudflare", host)
	if err == nil {
		for _, a := range addrs {
			out = append(out, Route{
				How:    "pinned " + a.String(),
				Client: at(host, a.String(), timeout),
			})
		}
	}
	return out
}

// ErrNoRoute is every way tried and none of them answering.
var ErrNoRoute = errors.New("nothing could reach it from this line")

// Fetch asks one question over whichever route answers first.
//
// An HTTP error is still an answer: these APIs put their refusals in a JSON
// body with a 403 on it, and a caller that only accepted 200 would report
// "unreachable" for "wrong password". What is not an answer is a body that is
// not JSON at all - that is the block page this line serves in place of the
// site, and the next route is worth trying.
func Fetch(
	ctx context.Context, host, url, method string,
	headers map[string]string, body []byte, timeout time.Duration,
) (status int, text []byte, err error) {

	var last error
	for _, route := range Routes(ctx, host, timeout) {
		var reader io.Reader
		if body != nil {
			reader = strings.NewReader(string(body))
		}
		req, mkErr := http.NewRequestWithContext(ctx, method, url, reader)
		if mkErr != nil {
			return 0, nil, mkErr
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}

		resp, doErr := route.Client.Do(req)
		if doErr != nil {
			last = doErr
			continue
		}
		got, readErr := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
		resp.Body.Close()
		if readErr != nil {
			last = readErr
			continue
		}
		// A block page is HTML with a 200 on it, and it is not this site
		// answering. Anything that parses as JSON is, whatever its status.
		if !looksJSON(got) {
			last = errors.New("a block page came back rather than " + host)
			continue
		}
		return resp.StatusCode, got, nil
	}
	if last == nil {
		last = ErrNoRoute
	}
	return 0, nil, last
}

func looksJSON(body []byte) bool {
	for _, b := range body {
		switch b {
		case ' ', '\t', '\r', '\n':
			continue
		case '{', '[':
			return true
		default:
			return false
		}
	}
	return false
}
