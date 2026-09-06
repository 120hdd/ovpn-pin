// Command probe asks pinned exits whether they are worth connecting to, and
// prints what each one said.
//
// It exists so the core can be argued with before there is a phone in the
// picture at all. Everything it calls is the code gomobile will build for
// Android and iOS, so an exit that answers here answers there.
//
//	go run ./cmd/probe -auth ../../.ovpn-auth ../../pinned/*.ovpn
//	go run ./cmd/probe -auth ../../.ovpn-auth -race ../../pinned/*.ovpn
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

func main() {
	auth := flag.String("auth", ".ovpn-auth", "file holding username and password")
	timeout := flag.Duration("timeout", 6*time.Second, "per-exit budget")
	width := flag.Int("width", core.RaceWidth, "how many to ask at once")
	race := flag.Bool("race", false, "stop at the first exit that says yes")
	flag.Parse()

	paths := flag.Args()
	if len(paths) == 0 {
		fmt.Fprintln(os.Stderr, "give it some pinned .ovpn files")
		os.Exit(2)
	}

	user, password, err := core.Credentials(*auth)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	if *race {
		runRace(paths, user, password, *timeout, *width)
		return
	}
	runAll(paths, user, password, *timeout, *width)
}

// runRace is what connecting will do: ask until one says yes, then stop.
func runRace(paths []string, user, password string, timeout time.Duration, width int) {
	var servers []core.Server
	for _, p := range paths {
		s, err := core.ReadConfig(p)
		if err != nil {
			continue
		}
		servers = append(servers, s)
	}

	started := time.Now()
	w, err := core.Race(context.Background(), servers, user, password, timeout, width,
		func(asked, total int) {
			// Progress on stderr, the answer on stdout. A run whose output
			// is piped somewhere then carries the result and not the
			// counting, and the carriage returns cannot land in the middle
			// of the line that matters.
			fmt.Fprintf(os.Stderr, "\r  asked %d of %d   ", asked, total)
		})
	fmt.Fprint(os.Stderr, "\r                                        \r")

	if err != nil {
		fmt.Fprintf(os.Stderr, "%v\n", err)
		os.Exit(1)
	}
	fmt.Printf("  %s\n", filepath.Base(w.Server.Path))
	fmt.Printf("  %s at %s\n", w.Server.Name, w.Server.Addr)
	fmt.Printf("\n  it answered in %.2fs, and the race took %.2fs\n",
		w.Took.Seconds(), time.Since(started).Seconds())
}

// runAll asks every one of them, which is the reachability test rather than
// the connect - slower on purpose, because the answer is the whole list.
func runAll(paths []string, user, password string, timeout time.Duration, width int) {
	type result struct {
		name string
		took time.Duration
		err  error
	}
	results := make([]result, len(paths))

	gate := make(chan struct{}, width)
	var wg sync.WaitGroup
	for i, path := range paths {
		wg.Add(1)
		go func(i int, path string) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()

			label := filepath.Base(path)
			s, err := core.ReadConfig(path)
			if err != nil {
				results[i] = result{label, 0, err}
				return
			}
			took, err := core.Ask(core.NewExit(s.Addr, s.Name, user, password), timeout)
			results[i] = result{label, took, err}
		}(i, path)
	}
	wg.Wait()

	live := results[:0:0]
	for _, r := range results {
		if r.err == nil {
			live = append(live, r)
		}
	}
	sort.Slice(live, func(a, b int) bool { return live[a].took < live[b].took })

	for _, r := range live {
		fmt.Printf("  %6.2fs  %s\n", r.took.Seconds(), r.name)
	}
	for _, r := range results {
		if r.err != nil {
			fmt.Printf("     --    %s\n             %v\n", r.name, r.err)
		}
	}
	fmt.Printf("\n%d of %d answered\n", len(live), len(results))
}
