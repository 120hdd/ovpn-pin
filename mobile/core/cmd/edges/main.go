// Command edges asks which Cloudflare addresses will carry a domain from
// this line, and prints what the rest of them said.
//
// The same question the desktop asks before it writes a gost config, asked
// from the Go core so the two can be compared. If this and the desktop
// disagree about an address, one of them is wrong and it matters which.
//
//	go run ./cmd/edges ui.example.com
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

func main() {
	timeout := flag.Duration("timeout", 2500*time.Millisecond, "per-address budget")
	width := flag.Int("width", 28, "how many to ask at once")
	all := flag.Bool("all", false, "print every address, not just the live ones")
	flag.Parse()

	domain := flag.Arg(0)
	if domain == "" {
		fmt.Fprintln(os.Stderr, "give it a domain")
		os.Exit(2)
	}

	ctx := context.Background()
	started := time.Now()

	if *all {
		for _, ip := range core.EdgeCandidates() {
			v, took := core.EdgeProbe(ctx, domain, ip, *timeout)
			fmt.Printf("  %-16s %-8s %6.2fs\n", ip, v, took.Seconds())
		}
		return
	}

	// EdgesFor picks its own width; the flag is here for a line where
	// twenty-eight handshakes at once is itself the thing being measured.
	if *width != 28 {
		core.EdgeScan(ctx, domain, *timeout, *width)
	}
	live, said, err := core.EdgesFor(ctx, domain, *timeout)
	fmt.Printf("  asked %d addresses in %.1fs\n  %s\n\n",
		len(core.EdgeCandidates()), time.Since(started).Seconds(), said)

	if err != nil {
		fmt.Fprintf(os.Stderr, "  %v\n", err)
		os.Exit(1)
	}
	for i, ip := range live {
		fmt.Printf("  %d. %s\n", i+1, ip)
	}
}
