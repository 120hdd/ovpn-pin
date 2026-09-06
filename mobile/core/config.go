// Package core is the half of Relay that has to exist on a phone.
//
// Everything here is a port of the Python this repo already argued over, so
// where the two disagree the Python is the one that was measured and this one
// is wrong. The names are kept deliberately close for that reason:
// ReadConfig is read_config, Exit is Exit, Ask is ask_exit.
//
// Go rather than Kotlin or Swift because of one line in exit.go. Dropping SNI
// while still proving the name is a thing crypto/tls does in a struct literal
// and the platform TLS stacks do reluctantly or not at all - and gomobile
// builds this same file for both phones, so it is written once.
package core

import (
	"bufio"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
)

// The name is carried in a comment because nothing else in an .ovpn file has
// anywhere to put it: pinning overwrites the remote line with the address,
// and the name it was pinned from would otherwise be lost with it.
//
//	# pinned by Resolve-OvpnRemote on 2026-08-28 19:23 - cloudflare over DoH
//	#   ad-leu.prod.surfshark.com -> 62.197.152.149
var (
	pinComment = regexp.MustCompile(`^#\s+(\S+)\s+->\s+(\d{1,3}(?:\.\d{1,3}){3})\s*$`)
	remoteLine = regexp.MustCompile(`^\s*remote\s+(\S+)`)
)

// Server is one pinned exit: where to dial, and who the certificate there has
// to claim to be.
type Server struct {
	Addr string // the pinned IPv4, dialled literally
	Name string // the name it was pinned from, verified but never sent
	Path string

	// Filled in by Scan from the filename rather than from the file. All of
	// it is in the name a pinned config is written under, and reading 147
	// files to learn what 147 names already say is a second of a phone's
	// time for nothing.
	File     string   // the basename, which is what the desktop keys reach by
	Country  string   // two letters, canonical - `uk`, never `gb`
	City     string   // three letters, as the provider writes them
	Provider string   // "surfshark" or "windscribe"
	Seconds  *float64 // what the last sweep measured, if there was one
}

// ReadConfig returns the address a config is pinned to and the name it was
// pinned from.
//
// A config whose remote is still a name is refused rather than resolved.
// Resolving it here would use the phone's resolver, which is the one thing
// this whole repo exists to stop trusting.
func ReadConfig(path string) (Server, error) {
	f, err := os.Open(path)
	if err != nil {
		return Server{}, err
	}
	defer f.Close()

	var name, addr string
	scan := bufio.NewScanner(f)
	for scan.Scan() {
		line := scan.Text()
		if name == "" {
			if m := pinComment.FindStringSubmatch(line); m != nil {
				name = m[1]
				continue
			}
		}
		if addr == "" {
			if m := remoteLine.FindStringSubmatch(line); m != nil {
				addr = m[1]
			}
		}
		// The certificate blocks are the bulk of the file and hold neither.
		if name != "" && addr != "" {
			break
		}
	}
	if err := scan.Err(); err != nil {
		return Server{}, err
	}

	base := filepath.Base(path)
	if addr == "" {
		return Server{}, fmt.Errorf("%s has no remote line", base)
	}
	if ip, err := netip.ParseAddr(addr); err != nil || !ip.Is4() {
		return Server{}, fmt.Errorf(
			"%s is not pinned: its remote is still the name %q, "+
				"which is the thing your resolver lies about", base, addr)
	}
	return Server{Addr: addr, Name: name, Path: path}, nil
}
