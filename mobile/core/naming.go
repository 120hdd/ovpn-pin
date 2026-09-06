package core

import "sync"

// Which application opened a connection.
//
// The desktop answers this by looking up the owner of a local port, and says
// so plainly. A phone cannot do that - an Android app cannot see another
// app's sockets - and for a while this column was left empty here with a
// comment explaining why.
//
// The comment was wrong. Android 10 added ConnectivityManager
// .getConnectionOwnerUid, which takes the same five-tuple the tun already has
// and gives back the uid that owns it; PackageManager turns that into a name.
// It is exactly the desktop's port-owner lookup wearing a different API.
//
// It lives behind an interface for the same reason Protector does: the call
// is Android's, the caller is Go, and gomobile carries interfaces. Nothing in
// core knows what a uid is.
type Namer interface {
	// Name is the application that opened this connection, or "" when the
	// platform will not say. Both addresses are "ip:port".
	//
	// Called once per connection, on the goroutine carrying it, so it must
	// not block for long - a lookup that takes a second would put a second
	// on the front of every connection the phone makes.
	Name(protocol int, source, destination string) string
}

var (
	namerMu sync.RWMutex
	namer   Namer
)

// SetNamer hands over the lookup. Safe to call with nil, which is what a
// platform that cannot answer should do rather than returning "" forever from
// a call that costs a bridge crossing.
func SetNamer(n Namer) {
	namerMu.Lock()
	defer namerMu.Unlock()
	namer = n
}

// whoOpened asks, if anyone can answer.
func whoOpened(protocol int, source, destination string) string {
	namerMu.RLock()
	n := namer
	namerMu.RUnlock()
	if n == nil {
		return ""
	}
	return n.Name(protocol, source, destination)
}
