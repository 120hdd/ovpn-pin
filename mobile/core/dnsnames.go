package core

import (
	"encoding/binary"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// Which name an address was looked up under.
//
// The desktop never needs this: it is an HTTP proxy, so every connection
// arrives as `CONNECT telegram.org:443` and the name is simply there. A tun
// gets packets. By the time a connection reaches the stack the application
// has already resolved the name and thrown it away, and all that is left is
// 149.154.167.91 - which is a true answer to "what went where" and a useless
// one.
//
// But this app answered that lookup. Every DNS query on the phone is hijacked
// and re-asked over HTTPS through the exit, which means the answers pass
// through here on the way back. Remembering them costs a map.
//
// It is a guess, and deliberately a cheap one: several names can share an
// address, and the last one to be looked up wins. That is right far more
// often than it is wrong, and when it is wrong it is wrong about a CDN whose
// addresses are shared on purpose. Nothing is decided on the strength of it -
// it is a label in a list.
type dnsNames struct {
	mu    sync.Mutex
	byIP  map[netip.Addr]nameSeen
	limit int
}

type nameSeen struct {
	name string
	at   time.Time
}

func newDNSNames() *dnsNames {
	return &dnsNames{byIP: map[netip.Addr]nameSeen{}, limit: 2048}
}

// Name is what that address was most recently looked up as, or "".
func (d *dnsNames) Name(ip netip.Addr) string {
	if d == nil {
		return ""
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.byIP[ip].name
}

func (d *dnsNames) remember(ip netip.Addr, name string) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if len(d.byIP) >= d.limit {
		// Oldest out. A phone that has been up for a day has looked up more
		// names than any list will show, and the ones worth labelling are
		// the ones looked up recently.
		var oldest netip.Addr
		var when time.Time
		for k, v := range d.byIP {
			if when.IsZero() || v.at.Before(when) {
				oldest, when = k, v.at
			}
		}
		delete(d.byIP, oldest)
	}
	d.byIP[ip] = nameSeen{name: name, at: time.Now()}
}

// learn reads the addresses out of a DNS answer and files them under the name
// that was asked for.
//
// A deliberately small parser: the question's name, then every A and AAAA in
// the answer section. Anything it does not understand it stops at, because
// the cost of being wrong is a missing label and the cost of being clever is
// a parser nobody checks.
func (d *dnsNames) learn(msg []byte) {
	if d == nil || len(msg) < 12 {
		return
	}

	qdcount := int(binary.BigEndian.Uint16(msg[4:6]))
	ancount := int(binary.BigEndian.Uint16(msg[6:8]))
	if qdcount < 1 || ancount < 1 {
		return
	}

	pos := 12
	name, pos, ok := readName(msg, pos)
	if !ok || name == "" {
		return
	}
	pos += 4 // qtype, qclass
	for i := 1; i < qdcount; i++ {
		_, pos, ok = readName(msg, pos)
		if !ok {
			return
		}
		pos += 4
	}

	for i := 0; i < ancount; i++ {
		_, pos, ok = readName(msg, pos)
		if !ok || pos+10 > len(msg) {
			return
		}
		rrType := binary.BigEndian.Uint16(msg[pos : pos+2])
		rdLen := int(binary.BigEndian.Uint16(msg[pos+8 : pos+10]))
		pos += 10
		if pos+rdLen > len(msg) {
			return
		}

		switch {
		case rrType == 1 && rdLen == 4:
			if ip, ok := netip.AddrFromSlice(msg[pos : pos+4]); ok {
				d.remember(ip.Unmap(), name)
			}
		case rrType == 28 && rdLen == 16:
			if ip, ok := netip.AddrFromSlice(msg[pos : pos+16]); ok {
				d.remember(ip, name)
			}
		}
		pos += rdLen
	}
}

// readName walks a DNS name, following compression pointers.
//
// Iterative, and that is not a style preference. Written as a recursion, the
// loop guard lives in a local - and the recursive call gets a fresh one, so a
// pointer at offset 12 that points to offset 12 recurses until the stack
// runs out. The test that builds exactly that message found it:
//
//	runtime: goroutine stack exceeds 1000000000-byte limit
//	dnsnames.go:161 ... dnsnames.go:161 ... dnsnames.go:161
//
// A budget on hops cannot be reset by anything, because there is nothing to
// reset it. Every hop must also go strictly backwards, which is what the
// format guarantees and what makes the budget sufficient rather than merely
// comforting.
func readName(msg []byte, pos int) (string, int, bool) {
	var parts []string
	start := pos
	after := -1
	hops := 0

	for {
		if pos >= len(msg) {
			return "", start, false
		}
		n := int(msg[pos])
		if n == 0 {
			pos++
			break
		}
		if n&0xC0 == 0xC0 {
			if pos+1 >= len(msg) {
				return "", start, false
			}
			target := int(binary.BigEndian.Uint16(msg[pos:pos+2]) & 0x3FFF)
			// Backwards only, and a budget besides. Either alone would do;
			// together they mean a malformed message costs a few reads.
			if target >= pos || target >= len(msg) || hops > 16 {
				return "", start, false
			}
			hops++
			if after < 0 {
				// Where the caller carries on is fixed by the first pointer;
				// everything after it is read from elsewhere in the message.
				after = pos + 2
			}
			pos = target
			continue
		}
		pos++
		if pos+n > len(msg) {
			return "", start, false
		}
		parts = append(parts, string(msg[pos:pos+n]))
		pos += n
	}

	if after < 0 {
		after = pos
	}
	return strings.Join(parts, "."), after, true
}
