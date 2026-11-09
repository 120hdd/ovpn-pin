package relay

import (
	"strconv"
	"strings"

	"github.com/ERelay/ovpn-pin/mobile/core"
)

// Split tunnelling, flattened for the bridge: strings joined by newlines and
// commas where core takes slices. See core/split.go for what the rules mean.

// SplitIranApps is the Iran preset's package names, one per line, for the
// phone to look for among what is installed.
func SplitIranApps() string { return strings.Join(core.IranApps(), "\n") }

// SplitIranCounts is "domains,ranges" in the Iran preset, for a help line.
func SplitIranCounts() string {
	d, r := core.IranCounts()
	return strconv.Itoa(d) + "," + strconv.Itoa(r)
}

// SplitNormalize is one entry in the form it will be kept in, or the reason
// it cannot be one - for the settings screen to say before it is saved.
func SplitNormalize(entry string) (string, error) { return core.NormalizeSplitEntry(entry) }

// Owner is the uid behind a connection, from the platform - the phone's
// ConnectivityManager.getConnectionOwnerUid. -1 when it will not say.
type Owner interface {
	Uid(protocol int, source, destination string) int
}

// SetOwner hands that lookup over. Package-level, like SetNamer.
func SetOwner(o Owner) {
	if o == nil {
		core.SetOwner(nil)
		return
	}
	core.SetOwner(o)
}

// SetSplit decides which connections go around the exit, for the next
// StartTunnel or StartServerTunnel - and for the one that is running, if
// there is one.
//
//	mode       "off", "bypass" or "only"
//	iran       the Iran preset's domains and address ranges
//	entries    the person's own domains, addresses and prefixes, one a line
//	uids       the listed apps' uids, comma-separated - only where apps have
//	           to be told apart inside the tun ("only", with sites listed too)
//	directDns  the line's own resolver, "" when the phone could not find it
func (c *Client) SetSplit(mode string, iran bool, entries string, uids string, directDns string) error {
	var ids []int
	for _, f := range strings.Split(uids, ",") {
		if f = strings.TrimSpace(f); f == "" {
			continue
		}
		n, err := strconv.Atoi(f)
		if err != nil {
			return err
		}
		ids = append(ids, n)
	}
	s, err := core.NewSplit(mode, iran, strings.Split(entries, "\n"), ids, directDns)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.split = s
	if c.tunnel != nil {
		c.tunnel.SetSplit(s)
	}
	return nil
}
