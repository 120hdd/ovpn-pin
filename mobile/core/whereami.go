package core

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// WhereAmI is the address this machine comes out at, and the country a
// content network puts it in.
//
// Asked of Cloudflare's trace endpoint, by address rather than by name, for
// the same reason doh.go pins its resolvers: on the lines this is for, the
// name of anything worth asking is forged. 1.1.1.1 answers /cdn-cgi/trace
// over TLS, and for an address literal Go sends no SNI - so the question is
// unannounced and the answer is proved, without a line of code here.
//
// Asked directly, never through the tunnel. This is the "before" of the two
// addresses the window shows; the exit's own view is what the tunnel is for.
// On Android the app is excluded from its own VPN, so a request from this
// process still leaves by the real line even while the tunnel is up - which
// is what makes "your address" stay true after connecting rather than
// becoming a second copy of the exit's.
func WhereAmI(ctx context.Context, timeout time.Duration) (ip, country string, err error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		"https://1.1.1.1/cdn-cgi/trace", nil)
	if err != nil {
		return "", "", err
	}

	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			// No proxy, and said explicitly: http.DefaultTransport reads
			// HTTP_PROXY from the environment, and a phone that had one set
			// would answer this question about the proxy instead.
			Proxy:               nil,
			DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
			TLSHandshakeTimeout: timeout,
		},
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()

	// The trace endpoint answers in lines of key=value and is a few hundred
	// bytes. Capped anyway: an endpoint that answered with a megabyte would
	// otherwise be believed.
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
	return ip, country, nil
}

// WhereAmIJSON is the same answer in the shape the window's onRealIp expects.
// An empty ip is how it says it does not know, which the page already draws
// as "unknown" rather than as an error.
func WhereAmIJSON(timeout time.Duration) string {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	ip, country, err := WhereAmI(ctx, timeout)
	if err != nil {
		ip, country = "", ""
	}
	b, _ := json.Marshal(map[string]string{"ip": ip, "country": country})
	return string(b)
}
