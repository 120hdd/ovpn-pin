package core

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// SeenAs is where the internet says the traffic came out, asked through the
// exit rather than about it.
//
// The distinction is the whole point, and the window's own comment makes it:
// an exit's proxy address and its egress address are not the same number.
// 172.216.14.101 is what this app dials; 172.216.14.102 is what a website
// sees. Putting the first under "Seen as" would be a claim nothing checked,
// which is the one thing that readout must never carry.
//
// So the question goes through the tunnel: one CONNECT to Cloudflare's trace
// endpoint, by address for the reasons doh.go gives, and the answer is
// whatever that exit actually looks like from the far side.
func SeenAs(ctx context.Context, e Way, timeout time.Duration) (ip, country string, err error) {
	if e == nil {
		return "", "", errors.New("no way out to ask through")
	}

	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: nil,
			// Through the exit, not past it. TLS-in-TLS: the outer handshake
			// announces nothing and the inner one names an address, so
			// neither puts a name on the wire.
			DialContext: func(_ context.Context, _, addr string) (net.Conn, error) {
				conn, spare, err := e.Open(addr, timeout)
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

	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://1.1.1.1/cdn-cgi/trace", nil)
	if err != nil {
		return "", "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 8192))
	if err != nil {
		return "", "", err
	}
	for _, line := range strings.Split(string(body), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		switch k {
		case "ip":
			ip = v
		case "loc":
			country = strings.ToLower(v)
		}
	}
	if ip == "" {
		return "", "", errors.New("the trace came back without an address in it")
	}
	return ip, country, nil
}

// SeenAsJSON is the same answer in the shape the window reads.
//
// An empty object rather than an error when the check does not answer: the
// page has its own word for that - "unconfirmed", and a hint that says
// connected but unverified - and it is a better thing to show than a failure,
// because the tunnel is up either way.
func SeenAsJSON(addr, name, user, password string, timeout time.Duration) string {
	out, _ := SeenAsThrough(NewExit(addr, name, user, password), timeout)
	return out
}

// SeenAsThrough is SeenAsJSON for any way out, which is what the server modes
// need: the question is the same one, and only the thing carrying it differs.
//
// The error comes back as well as the empty object, because a "test this
// server" button wants the reason and a status readout does not.
func SeenAsThrough(w Way, timeout time.Duration) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	ip, country, err := SeenAs(ctx, w, timeout)
	if err != nil {
		return "{}", err
	}
	b, _ := json.Marshal(map[string]string{"ip": ip, "country": country})
	return string(b), nil
}
