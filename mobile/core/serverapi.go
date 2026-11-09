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
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// SetExit points the server's multi-IP mode at exitIP, with that exit's
// provider credential. host is the server's IP or domain; pin is its
// certificate's SHA-256 when it is reached by IP.
func SetExit(host, apiPassword, pin, exitIP, user, password, name string, timeout time.Duration) error {
	return setChain(host, apiPassword, pin, "exit-chain", exitIP, user, password, name, timeout)
}

// SetSlot points one exported location - loc-<k> on the server, which the
// links handed to other devices are routed to - at exitIP. The same node as
// SetExit writes, under another chain's name.
func SetSlot(host, apiPassword, pin string, k int, exitIP, user, password, name string, timeout time.Duration) error {
	if k < 1 {
		return fmt.Errorf("slot %d is not one the server has: they start at 1", k)
	}
	return setChain(host, apiPassword, pin, fmt.Sprintf("loc-%d", k), exitIP, user, password, name, timeout)
}

func setChain(host, apiPassword, pin, chain, exitIP, user, password, name string, timeout time.Duration) error {
	if name == "" {
		name = "current"
	}
	body, _ := json.Marshal(map[string]any{
		"name": chain,
		"hops": []any{map[string]any{"name": "exit", "nodes": []any{map[string]any{
			"name":      name,
			"addr":      net.JoinHostPort(exitIP, "443"),
			"connector": map[string]any{"type": "http", "auth": map[string]any{"username": user, "password": password}},
			"dialer":    map[string]any{"type": "tls", "tls": map[string]any{"secure": false}},
		}}}},
	})
	_, err := ServerAPI(host, apiPassword, pin, http.MethodPut, "config/chains/"+chain, body, timeout)
	return err
}

// ChainJSON is one chain as the server has it now, for putting back later -
// what an export does with the server's own exit-chain, which finding each
// location's best exit moves about.
func ChainJSON(host, apiPassword, pin, chain string, timeout time.Duration) (string, error) {
	out, err := ServerAPI(host, apiPassword, pin, http.MethodGet, "config/chains/"+chain, nil, timeout)
	if err != nil {
		return "", err
	}
	// gost wraps the object: {"data": {...}}.
	var wrapped struct {
		Data json.RawMessage `json:"data"`
	}
	if json.Unmarshal(out, &wrapped) != nil || len(wrapped.Data) == 0 || string(wrapped.Data) == "null" {
		return "", fmt.Errorf("the server has no chain called %s", chain)
	}
	return string(wrapped.Data), nil
}

// PutChainJSON puts back a chain ChainJSON read.
func PutChainJSON(host, apiPassword, pin, chain, body string, timeout time.Duration) error {
	_, err := ServerAPI(host, apiPassword, pin, http.MethodPut, "config/chains/"+chain, []byte(body), timeout)
	return err
}

// PersistServer writes the server's running configuration to the file it
// starts from, so what was set through the API outlives a restart. Without
// it, exported locations would all go back to the installer's placeholder -
// one that refuses - the next time the server rebooted.
func PersistServer(host, apiPassword, pin string, timeout time.Duration) error {
	_, err := ServerAPI(host, apiPassword, pin, http.MethodPost,
		"config?format=yaml&path=/etc/gost/config.yaml", nil, timeout)
	return err
}

// ServerAPI is one request to the server's API, straight to it - protected
// out of the phone's own tunnel, and pinned when the server is an address.
func ServerAPI(host, apiPassword, pin, method, path string, body []byte, timeout time.Duration) ([]byte, error) {
	tr := &http.Transport{DialContext: (&net.Dialer{Timeout: timeout, Control: dialControl}).DialContext}
	if net.ParseIP(host) != nil {
		tr.TLSClientConfig = PinnedTLS(pin, nil)
	}
	client := &http.Client{Timeout: timeout, Transport: tr}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, "https://"+host+"/api/"+path, reader)
	if err != nil {
		return nil, err
	}
	req.SetBasicAuth("relay", apiPassword)
	req.Header.Set("User-Agent", "Relay (ovpn-pin phone)")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusUnauthorized:
		return nil, fmt.Errorf("the server refused the API password")
	case resp.StatusCode == http.StatusNotFound && strings.HasPrefix(path, "config/chains/loc-"):
		return nil, fmt.Errorf("the server has no exported locations - run the installer from this version again")
	case resp.StatusCode/100 != 2:
		return nil, fmt.Errorf("the server answered %s to %s %s", resp.Status, method, strings.SplitN(path, "?", 2)[0])
	}
	return out, nil
}
