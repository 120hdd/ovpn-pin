package core

// The user's own server's API: where server + exit is told which provider
// exit to leave by. The desktop's tunnel.set_exit, for the phone.
//
// One PUT, and the change takes effect on the next connection through /ex,
// with nothing restarted at either end. The account goes with it - the
// chosen exit's own provider's - so the server never needs one of its own
// and the install line never carries one.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"time"
)

// SetExit points the server's multi-IP mode at exitIP, with that exit's
// provider credential. host is the server's IP or domain; pin is its
// certificate's SHA-256 when it is reached by IP.
func SetExit(host, apiPassword, pin, exitIP, user, password, name string, timeout time.Duration) error {
	if name == "" {
		name = "current"
	}
	body, _ := json.Marshal(map[string]any{
		"name": "exit-chain",
		"hops": []any{map[string]any{"name": "exit", "nodes": []any{map[string]any{
			"name":      name,
			"addr":      net.JoinHostPort(exitIP, "443"),
			"connector": map[string]any{"type": "http", "auth": map[string]any{"username": user, "password": password}},
			"dialer":    map[string]any{"type": "tls", "tls": map[string]any{"secure": false}},
		}}}},
	})

	tr := &http.Transport{DialContext: (&net.Dialer{Timeout: timeout, Control: dialControl}).DialContext}
	if net.ParseIP(host) != nil {
		tr.TLSClientConfig = PinnedTLS(pin, nil)
	}
	client := &http.Client{Timeout: timeout, Transport: tr}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPut,
		"https://"+host+"/api/config/chains/exit-chain", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.SetBasicAuth("relay", apiPassword)
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusUnauthorized:
		return fmt.Errorf("the server refused the API password")
	case resp.StatusCode/100 != 2:
		return fmt.Errorf("the server answered %s when asked to change exit", resp.Status)
	}
	return nil
}
