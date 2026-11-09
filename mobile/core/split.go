package core

// Split tunnelling: which connections go through the exit and which go
// straight out by the phone's own line.
//
// One mode decides the direction and one list decides what it applies to:
//
//	off     everything through the exit - what there was before this
//	bypass  everything through the exit except what is listed
//	only    only what is listed goes through the exit
//
// The list is names (a domain means itself and everything under it),
// addresses and prefixes, and - in "only" - the uids of apps. Apps in
// "bypass" never reach this file: Android leaves them out of the VPN
// altogether, which is both cheaper and the only way an app that refuses to
// run under a VPN stops seeing one. In "only" an app is listed here instead
// when sites are listed too, because then every app has to enter the tun for
// the sites to be found, and the app has to be recognised once it is inside.
//
// What a connection is matched by is what the tun can know about it before
// the first byte is sent anywhere: its address, the name the phone looked
// that address up under (dnsnames.go saw the answer go past), and - when
// neither settles it and names are listed - the name in its TLS ClientHello.
//
// The Iran preset is three lists kept in lists/: Iran's registered address
// ranges, the domains of Iranian services, and Iranian apps' package names.
// Embedded, because a list fetched at run time would be fetched through
// whichever way out was already up, from a line that filters.

import (
	_ "embed"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

//go:embed lists/iran-ipv4.txt
var iranIPv4 string

//go:embed lists/iran-domains.txt
var iranDomains string

//go:embed lists/iran-apps.txt
var iranApps string

const (
	SplitOff    = "off"
	SplitBypass = "bypass"
	SplitOnly   = "only"
)

// Split is one decided set of rules. Built once per connect and never
// changed after - a new set is a new Split - so it is read from every
// connection's goroutine without a lock.
type Split struct {
	mode  string
	names map[string]struct{}
	nets  []span
	uids  map[int]struct{}

	// The resolver of the line underneath, for names that go straight out.
	// Asked directly rather than through the exit, because a name that is
	// going to be reached from here should be answered as seen from here:
	// Iranian services answer inside Iran with addresses abroad would not.
	dns netip.AddrPort
}

// span is an inclusive range of IPv4 addresses. Ranges rather than
// prefixes, because a sorted list of non-overlapping ranges answers "is
// this address in it" with one binary search however many prefixes went in.
type span struct{ lo, hi uint32 }

// NewSplit builds the rules. `entries` are what the person typed, already
// normalised or not - each is normalised again here and a bad one is an
// error rather than a rule that silently never matches. `directDNS` is the
// underlying network's resolver, "" when the platform did not say.
func NewSplit(mode string, iran bool, entries []string, uids []int, directDNS string) (*Split, error) {
	switch mode {
	case SplitOff, SplitBypass, SplitOnly:
	case "":
		mode = SplitOff
	default:
		return nil, errors.New("split mode must be off, bypass or only, not " + strconv.Quote(mode))
	}
	s := &Split{mode: mode, names: map[string]struct{}{}, uids: map[int]struct{}{}}

	var prefixes []netip.Prefix
	add := func(entry string) error {
		norm, err := NormalizeSplitEntry(entry)
		if err != nil {
			return err
		}
		if p, err := netip.ParsePrefix(norm); err == nil {
			prefixes = append(prefixes, p)
		} else if a, err := netip.ParseAddr(norm); err == nil {
			prefixes = append(prefixes, netip.PrefixFrom(a, 32))
		} else {
			s.names[norm] = struct{}{}
		}
		return nil
	}
	if iran {
		for _, line := range listLines(iranDomains) {
			if err := add(line); err != nil {
				return nil, errors.New("the Iran domain list: " + err.Error())
			}
		}
		for _, line := range listLines(iranIPv4) {
			if err := add(line); err != nil {
				return nil, errors.New("the Iran address list: " + err.Error())
			}
		}
	}
	for _, e := range entries {
		if strings.TrimSpace(e) == "" {
			continue
		}
		if err := add(e); err != nil {
			return nil, err
		}
	}
	s.nets = spans(prefixes)
	for _, u := range uids {
		if u >= 0 {
			s.uids[u] = struct{}{}
		}
	}

	if directDNS != "" {
		a, err := netip.ParseAddr(directDNS)
		if err != nil || !a.Is4() {
			return nil, errors.New("the direct resolver is not an IPv4 address: " + strconv.Quote(directDNS))
		}
		s.dns = netip.AddrPortFrom(a, 53)
	}
	return s, nil
}

// Active is whether there is anything to decide. A split whose list came
// out empty decides nothing either way, so it is treated as off: "only
// nothing" through the exit would be a VPN that carries nothing, and the
// window says so before it gets here.
func (s *Split) Active() bool {
	if s == nil || s.mode == SplitOff {
		return false
	}
	return len(s.names) > 0 || len(s.nets) > 0 || len(s.uids) > 0
}

// Mode is what was asked for, for a status line.
func (s *Split) Mode() string {
	if s == nil {
		return SplitOff
	}
	return s.mode
}

// NeedsName is whether a connection's name can change the answer - that is,
// whether any names are listed. Without any, peeking at a ClientHello would
// cost every connection a read for nothing.
func (s *Split) NeedsName() bool { return s.Active() && len(s.names) > 0 }

// NeedsOwner is whether the app behind a connection can change the answer.
// Asking costs a binder call per connection, so only when it can.
func (s *Split) NeedsOwner() bool { return s.Active() && len(s.uids) > 0 }

// NameListed is whether a name, or any domain above it, is on the list.
func (s *Split) NameListed(name string) bool {
	if s == nil || name == "" {
		return false
	}
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	for {
		if _, ok := s.names[name]; ok {
			return true
		}
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return false
		}
		name = name[i+1:]
	}
}

// AddrListed is whether an address falls in a listed range. IPv4 only, like
// the tun: an IPv6 address is never listed.
func (s *Split) AddrListed(ip netip.Addr) bool {
	if s == nil {
		return false
	}
	ip = ip.Unmap()
	if !ip.Is4() || len(s.nets) == 0 {
		return false
	}
	v := be32(ip)
	// The first range starting above v; the one before it is the only one
	// that can hold v, since the ranges are sorted and do not overlap.
	i := sort.Search(len(s.nets), func(i int) bool { return s.nets[i].lo > v })
	return i > 0 && s.nets[i-1].hi >= v
}

// UidListed is whether an app is listed (in "only", the one place apps are).
func (s *Split) UidListed(uid int) bool {
	if s == nil || uid < 0 {
		return false
	}
	_, ok := s.uids[uid]
	return ok
}

// Direct turns "is it listed" into "does it go around the exit", which is
// the same question asked the right way round for each mode.
func (s *Split) Direct(listed bool) bool {
	if !s.Active() {
		return false
	}
	if s.mode == SplitBypass {
		return listed
	}
	return !listed
}

// DirectDNS is whether a lookup should be asked of the line's own resolver
// rather than through the exit. Only names that are going straight out, and
// only in "bypass": in "only", a name not on the list may still be looked
// up by an app that is, and an answer from this line's resolver is the
// forged one for anything filtered here. Through the exit, every answer is
// the true one, and the direct connections that follow work just as well.
func (s *Split) DirectDNS(name string) bool {
	return s.Active() && s.mode == SplitBypass && s.NameListed(name)
}

// Resolver is the line's own resolver, when the platform said what it was.
func (s *Split) Resolver() (netip.AddrPort, bool) {
	if s == nil || !s.dns.IsValid() {
		return netip.AddrPort{}, false
	}
	return s.dns, true
}

// NormalizeSplitEntry turns what somebody typed or pasted into the one form
// a rule is kept in, or says why it cannot be one.
//
//	https://WWW.Digikala.com/x?y  ->  www.digikala.com
//	*.digikala.com, .digikala.com ->  digikala.com
//	digikala.com:443              ->  digikala.com
//	ایران                         ->  xn--mgba3a4f16a
//	5.6.7.8                       ->  5.6.7.8
//	5.6.7.8/16                    ->  5.6.0.0/16
//
// IPv6 is refused rather than kept: nothing on either side carries it, so a
// rule for it would be a rule that never matches.
func NormalizeSplitEntry(text string) (string, error) {
	s := strings.ToLower(strings.TrimSpace(text))
	if s == "" {
		return "", errors.New("empty")
	}
	if p, err := netip.ParsePrefix(s); err == nil {
		if !p.Addr().Is4() {
			return "", errors.New(strconv.Quote(text) + " is IPv6, which is not carried")
		}
		return p.Masked().String(), nil
	}
	if i := strings.Index(s, "://"); i >= 0 {
		s = s[i+3:]
	}
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	if i := strings.LastIndexByte(s, '@'); i >= 0 {
		s = s[i+1:]
	}
	if host, port, err := net.SplitHostPort(s); err == nil {
		if _, perr := strconv.Atoi(port); perr == nil {
			s = host
		}
	}
	s = strings.Trim(s, "[]")
	if a, err := netip.ParseAddr(s); err == nil {
		if !a.Unmap().Is4() {
			return "", errors.New(strconv.Quote(text) + " is IPv6, which is not carried")
		}
		return a.Unmap().String(), nil
	}
	s = strings.TrimPrefix(s, "*")
	s = strings.Trim(s, ".")
	if s == "" {
		return "", errors.New(strconv.Quote(text) + " is not a domain or an address")
	}
	ascii, ok := toASCII(s)
	if !ok {
		return "", errors.New(strconv.Quote(text) + " is not a domain or an address")
	}
	if len(ascii) > 253 {
		return "", errors.New(strconv.Quote(text) + " is longer than a domain can be")
	}
	for _, label := range strings.Split(ascii, ".") {
		if !validLabel(label) {
			return "", errors.New(strconv.Quote(text) + " is not a domain or an address")
		}
	}
	return ascii, nil
}

// IranApps is the preset's package names, for the platform to look for.
func IranApps() []string { return listLines(iranApps) }

// IranCounts is how much the preset holds, for a help line.
func IranCounts() (domains, ranges int) {
	return len(listLines(iranDomains)), len(listLines(iranIPv4))
}

// toASCII spells a name the way DNS carries it: each label that is not
// already ASCII as "xn--" and its Punycode (RFC 3492). Lowercased first and
// nothing more - no IDNA mapping tables, which would be a megabyte in the
// phone's library to change the answer for almost nobody. The desktop does
// the same with Python's punycode codec, so both agree on every name.
func toASCII(name string) (string, bool) {
	labels := strings.Split(name, ".")
	for i, l := range labels {
		if isASCII(l) {
			continue
		}
		if !utf8.ValidString(l) {
			return "", false
		}
		labels[i] = "xn--" + punycode(strings.ToLower(l))
	}
	return strings.Join(labels, "."), true
}

func isASCII(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			return false
		}
	}
	return true
}

// punycode is RFC 3492's encoder, for one label.
func punycode(label string) string {
	const (
		base, tmin, tmax, skew, damp = 36, 1, 26, 38, 700
		initialBias, initialN        = 72, 128
	)
	digit := func(d int) byte {
		if d < 26 {
			return byte('a' + d)
		}
		return byte('0' + d - 26)
	}
	adapt := func(delta, points int, first bool) int {
		if first {
			delta /= damp
		} else {
			delta /= 2
		}
		delta += delta / points
		k := 0
		for delta > ((base-tmin)*tmax)/2 {
			delta /= base - tmin
			k += base
		}
		return k + (base-tmin+1)*delta/(delta+skew)
	}

	runes := []rune(label)
	var out []byte
	for _, r := range runes {
		if r < 0x80 {
			out = append(out, byte(r))
		}
	}
	b := len(out)
	h := b
	if b > 0 {
		out = append(out, '-')
	}
	n, delta, bias := initialN, 0, initialBias
	for h < len(runes) {
		m := int(^uint(0) >> 1)
		for _, r := range runes {
			if int(r) >= n && int(r) < m {
				m = int(r)
			}
		}
		delta += (m - n) * (h + 1)
		n = m
		for _, r := range runes {
			if int(r) < n {
				delta++
			}
			if int(r) == n {
				q := delta
				for k := base; ; k += base {
					t := k - bias
					if t < tmin {
						t = tmin
					} else if t > tmax {
						t = tmax
					}
					if q < t {
						break
					}
					out = append(out, digit(t+(q-t)%(base-t)))
					q = (q - t) / (base - t)
				}
				out = append(out, digit(q))
				bias = adapt(delta, h+1, h == b)
				delta = 0
				h++
			}
		}
		delta++
		n++
	}
	return string(out)
}

func validLabel(l string) bool {
	if l == "" || len(l) > 63 || l[0] == '-' || l[len(l)-1] == '-' {
		return false
	}
	for i := 0; i < len(l); i++ {
		c := l[i]
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

// listLines is a list file without its comments and blank lines.
func listLines(text string) []string {
	var out []string
	for _, line := range strings.Split(text, "\n") {
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		if line = strings.TrimSpace(line); line != "" {
			out = append(out, line)
		}
	}
	return out
}

// spans sorts and merges prefixes into the ranges AddrListed searches.
func spans(prefixes []netip.Prefix) []span {
	out := make([]span, 0, len(prefixes))
	for _, p := range prefixes {
		p = p.Masked()
		if !p.Addr().Is4() {
			continue
		}
		lo := be32(p.Addr())
		hi := lo | (uint32(1)<<(32-p.Bits()) - 1)
		if p.Bits() == 0 {
			hi = ^uint32(0)
		}
		out = append(out, span{lo, hi})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].lo < out[j].lo })
	merged := out[:0]
	for _, r := range out {
		if n := len(merged); n > 0 && (merged[n-1].hi == ^uint32(0) || r.lo <= merged[n-1].hi+1) {
			if r.hi > merged[n-1].hi {
				merged[n-1].hi = r.hi
			}
			continue
		}
		merged = append(merged, r)
	}
	return merged
}

func be32(a netip.Addr) uint32 {
	b := a.As4()
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
}

// questionName is the name a DNS query asks about, or "" when it is not a
// one-question query this can read.
func questionName(msg []byte) string {
	if len(msg) < 12 || binary.BigEndian.Uint16(msg[4:6]) != 1 {
		return ""
	}
	name, _, ok := readName(msg, 12)
	if !ok {
		return ""
	}
	return name
}
