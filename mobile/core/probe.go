package core

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"time"
)

// probeTarget is asked for by name rather than address on purpose. The exit
// resolves it, not this machine - so a name that resolves at all is also
// evidence the tunnel carries DNS, and there is nothing to pin here.
const probeTarget = "www.cloudflare.com:443"

// Ask is whether the exit runs a proxy that takes these credentials, and how
// long it took to say so.
//
// It stops at the 200. Nothing is fetched, so this answers only that one
// question - which is the question that separates the exits worth trying from
// the rest, and it is the same question asked the same way as the race, on
// purpose: a reachability test that probed differently would be measuring
// something other than whether connecting is about to work.
func Ask(e *Exit, timeout time.Duration) (time.Duration, error) {
	started := time.Now()
	conn, _, err := e.Open(probeTarget, timeout)
	if err != nil {
		return 0, err
	}
	conn.Close()
	return time.Since(started), nil
}

// Credentials is the two-line file OpenVPN would have been given: username
// on the first line, password on the second.
func Credentials(path string) (user, password string, err error) {
	f, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	defer f.Close()

	var lines []string
	scan := bufio.NewScanner(f)
	for scan.Scan() && len(lines) < 2 {
		lines = append(lines, strings.TrimSpace(scan.Text()))
	}
	if err := scan.Err(); err != nil {
		return "", "", err
	}
	if len(lines) < 2 || lines[0] == "" || lines[1] == "" {
		return "", "", fmt.Errorf("%s should hold a username on one line and a "+
			"password on the next", path)
	}
	return lines[0], lines[1], nil
}
