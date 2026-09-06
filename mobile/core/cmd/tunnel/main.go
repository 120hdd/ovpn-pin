// Command tunnel opens the way out through your own server and says where it
// comes out.
//
// The whole path in one go, so that a failure names the layer it happened at
// rather than arriving as "could not connect": the edge scan, the websocket
// upgrade, the multiplexed session, the CONNECT, and then one request through
// it to see whose address the far end wears.
//
//	go run ./cmd/tunnel -domain ui.example.com -password ... -mode single
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

func main() {
	domain := flag.String("domain", "", "the tunnel domain")
	password := flag.String("password", "", "the tunnel password (CONNECT)")
	mode := flag.String("mode", "single", "single, multi or bulk")
	scan := flag.Bool("scan", true, "measure the edges first")
	timeout := flag.Duration("timeout", 15*time.Second, "budget per step")
	flag.Parse()

	if *domain == "" || *password == "" {
		fmt.Fprintln(os.Stderr, "need -domain and -password")
		os.Exit(2)
	}

	ctx := context.Background()

	var edges []string
	if *scan {
		fmt.Print("  edges    scanning ... ")
		found, said, err := core.EdgesFor(ctx, *domain, 2500*time.Millisecond)
		fmt.Println(said)
		if err != nil {
			// Not fatal. The name may still resolve and carry the tunnel on a
			// line that is only lying about the addresses, and saying so is
			// more use than stopping here.
			fmt.Printf("  edges    %v\n  edges    falling back to the name\n", err)
		}
		edges = found
		for i, ip := range edges {
			fmt.Printf("  edge %d   %s\n", i+1, ip)
		}
	}

	t := core.NewTunnelServer(*domain, *password, edges, core.TunnelMode(*mode))
	defer t.Close()

	fmt.Printf("  mode     %s\n", *mode)
	started := time.Now()
	conn, spare, err := t.Open("1.1.1.1:443", *timeout)
	if err != nil {
		fmt.Fprintf(os.Stderr, "  tunnel   %v\n", err)
		os.Exit(1)
	}
	defer conn.Close()
	if len(spare) > 0 {
		fmt.Printf("  tunnel   %d bytes arrived behind the answer\n", len(spare))
	}
	fmt.Printf("  tunnel   open in %.2fs\n", time.Since(started).Seconds())

	// One request through it, because an open tunnel that carries nothing is
	// the failure this is meant to catch.
	client := &http.Client{
		Timeout: *timeout,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(context.Context, string, string) (net.Conn, error) {
				c, sp, err := t.Open("1.1.1.1:443", *timeout)
				if err != nil {
					return nil, err
				}
				if len(sp) > 0 {
					c.Close()
					return nil, fmt.Errorf("the server spoke before it was asked to")
				}
				return c, nil
			},
		},
	}
	resp, err := client.Get("https://1.1.1.1/cdn-cgi/trace")
	if err != nil {
		fmt.Fprintf(os.Stderr, "  through  %v\n", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))
	for _, line := range strings.Split(string(body), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), "=")
		if ok && (k == "ip" || k == "loc" || k == "colo") {
			fmt.Printf("  %-8s %s\n", k, v)
		}
	}
}
