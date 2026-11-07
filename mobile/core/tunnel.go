//go:build android || linux || darwin

// Carrying the phone's traffic, rather than only finding somewhere to carry
// it to.
//
// sing-box is not here, and that is a decision rather than an omission. Its
// libbox wants a twenty-eight method platform interface implemented in Kotlin
// and again in Swift, a JSON config, a DNS engine and a rule engine - to
// arrive at the same place this file arrives at in three hundred lines,
// because the exit is an HTTPS proxy and a proxy has exactly one verb.
//
// What is borrowed is sing-tun, which is the part that is genuinely hard: a
// TCP/IP stack that turns the packets on a tun device back into connections.
// Everything above it is this repo's own CONNECT.
//
// The shape of it:
//
//	VpnService opens the tun and hands over a file descriptor
//	  -> sing-tun reassembles packets into connections
//	    -> TCP        one CONNECT through the exit, then bytes both ways
//	    -> UDP :53    the query, re-asked as DoH through the same exit
//	    -> UDP other  dropped, and said out loud below
package core

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// dnsThroughTunnel is where DNS queries are re-asked. Pinned, for the reason
// doh.go gives at length: the resolver's own name is forged on the lines this
// is for. It is reached through the exit, so the answer is the exit's view of
// the internet rather than this phone's.
const dnsThroughTunnel = "https://1.1.1.1/dns-query"

// Tunnel is one way out, carrying everything. See way.go for what a Way is.
type Tunnel struct {
	// The way out, behind a pointer so it can be changed under a running
	// stack. An exit that stops answering is replaced here, not by tearing
	// the interface down and building another: every app on the phone would
	// see its network vanish and come back, and for the seconds in between
	// its traffic would leave by the bare line.
	exit  atomic.Pointer[wayBox]
	stack tun.Stack
	dev   tun.Tun
	dns   *http.Client
	hosts *Hosts
	names *dnsNames

	// What the way out has been doing, counted from the traffic itself.
	health health

	closeOnce sync.Once
}

// wayBox is a Way that atomic.Pointer can hold - it takes a concrete type,
// and a Way is an interface.
type wayBox struct{ Way }

// way is what is carrying now.
func (t *Tunnel) way() Way { return t.exit.Load().Way }

// open is one connection through whatever is carrying, counted.
func (t *Tunnel) open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	conn, spare, err := t.way().Open(target, timeout)
	t.health.note(err)
	return conn, spare, err
}

// SetWay puts a different way out under the running stack.
//
// Connections already open stay on the way that opened them, and end when it
// does; everything opened from now on goes through w. The count starts again,
// because what the old exit did says nothing about the new one.
func (t *Tunnel) SetWay(w Way) error {
	if w == nil {
		return errors.New("no exit to carry anything to")
	}
	t.exit.Store(&wayBox{w})
	t.health.reset()
	// Idle DoH connections belong to the old way. Left in the pool, the next
	// query would be asked of the exit that was just given up on.
	if tr, ok := t.dns.Transport.(*http.Transport); ok {
		tr.CloseIdleConnections()
	}
	return nil
}

// Health is how many connections in a row the way out has failed, and how
// long ago it last answered one. Cheap: two numbers, nothing sent.
func (t *Tunnel) Health() (fails int, sinceOK time.Duration) {
	return t.health.read()
}

// Way is what is carrying now, for a check that has to go through it.
func (t *Tunnel) Way() Way { return t.way() }

// StartTunnel takes the file descriptor VpnService opened and starts carrying
// traffic through the exit.
//
// The descriptor is taken, not borrowed. sing-tun does not duplicate it and
// closes it when the tunnel ends, so the caller must hand over ownership -
// on Android that means detachFd() rather than fd. Holding it on both sides
// is what Android's fdsan kills the process for:
//
//	fdsan: attempted to close file descriptor 136, expected to be unowned,
//	actually owned by ParcelFileDescriptor
func StartTunnel(fd int, mtu int, exit Way) (started *Tunnel, err error) {
	// A panic here is not theoretical: sing-tun validates some of its options
	// by panicking rather than by returning, and a Go panic inside a JNI
	// library takes the app down with it - no Java stack, no message in the
	// window, just an app that closes. Turning construction-time panics into
	// errors costs nothing and means the phone can say what happened.
	//
	// Only construction. A panic on one of the stack's own goroutines, after
	// this returns, is still fatal and belongs upstream.
	defer func() {
		if r := recover(); r != nil {
			started, err = nil, fmt.Errorf("the tunnel could not be built: %v", r)
		}
	}()

	if exit == nil {
		return nil, errors.New("no exit to carry anything to")
	}
	if mtu <= 0 {
		mtu = TunMTU
	}

	t := &Tunnel{hosts: newHosts(), names: newDNSNames()}
	t.exit.Store(&wayBox{exit})
	t.health.reset()
	t.dns = &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			// Every DNS query is its own tunnel through the exit, and then
			// its own TLS inside that - which is what a proxied HTTPS request
			// is. The outer handshake announces nothing; the inner one names
			// 1.1.1.1, which is an address and so is not announced either.
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				// Through t rather than the exit it started with, so a query
				// asked after the exit was replaced goes out the new one - and
				// counted, because DNS is usually the first thing to notice.
				conn, spare, err := t.open(addr, 10*time.Second)
				if err != nil {
					return nil, err
				}
				if len(spare) > 0 {
					conn.Close()
					return nil, errors.New("the exit spoke before it was asked to")
				}
				return conn, nil
			},
			MaxIdleConnsPerHost: 4,
			IdleConnTimeout:     90 * time.Second,
		},
	}

	opts := tun.Options{
		FileDescriptor: fd,
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{TunAddress},
		// Android has already routed everything to this interface by the time
		// the descriptor exists. Asking sing-tun to route as well would have
		// it reach for netlink, which the app is not privileged to use.
		AutoRoute: false,
	}
	dev, err := tun.New(opts)
	if err != nil {
		return nil, err
	}
	t.dev = dev

	stack, err := tun.NewStack("gvisor", tun.StackOptions{
		Context:    context.Background(),
		Tun:        dev,
		TunOptions: opts,
		Handler:    (*handler)(t),
		Logger:     logger.NOP(),

		// Not optional, and not defaulted. A zero UDPTimeout reaches
		// NewUDPNat, which does not return an error for it - it panics, and a
		// Go panic inside a JNI library takes the whole app down with no Java
		// stack to read. Found by watching an app close itself on the phone.
		//
		// The values are sing-box's own. Nothing here carries UDP anyway,
		// beyond DNS which is hijacked before it is routed - so these govern a
		// table that stays close to empty. They still have to be set.
		UDPTimeout:  5 * time.Minute,
		ICMPTimeout: 30 * time.Second,
	})
	if err != nil {
		dev.Close()
		return nil, err
	}
	// gVisor rather than the system stack: the system one wants a raw socket
	// per connection, and an unprivileged Android app does not get those.
	if err := stack.Start(); err != nil {
		dev.Close()
		return nil, err
	}
	t.stack = stack
	return t, nil
}

// HostsJSON is what went where, in the shape the log sheet reads.
func (t *Tunnel) HostsJSON() string { return t.hosts.JSON() }

// Exit is the address traffic is currently leaving by, for a status line.
func (t *Tunnel) Exit() string {
	switch w := t.way().(type) {
	case nil:
		return ""
	case *Exit:
		return w.Addr
	case *TunnelServer:
		return w.Domain
	}
	return ""
}

func (t *Tunnel) Close() error {
	var err error
	t.closeOnce.Do(func() {
		if t.stack != nil {
			err = t.stack.Close()
		}
		if t.dev != nil {
			t.dev.Close()
		}
		if tr, ok := t.dns.Transport.(*http.Transport); ok {
			tr.CloseIdleConnections()
		}
	})
	return err
}

// handler is the Tunnel wearing sing-tun's interface. A distinct type so that
// the four methods the stack calls are not mixed in with the six a caller
// does - they run on different goroutines and answer to different owners.
type handler Tunnel

// JudgeFlow decides, per flow, before anything is dialled.
//
// The UDP answer is the honest one and it is worth saying plainly rather than
// discovering later: CONNECT carries a stream, so there is no UDP through
// this exit and there never will be. DNS is the exception because it can be
// re-asked over HTTPS, which is a stream. Everything else UDP is dropped, and
// almost everything copes - QUIC falls back to TCP when it gets nothing, and
// that fallback is what makes the web work here at all.
func (h *handler) JudgeFlow(network uint8, _ netip.AddrPort, destination netip.AddrPort, _ []byte) tun.FlowVerdict {
	if destination.Port() == 53 {
		return tun.FlowVerdict{Action: tun.ActionHijackDNS}
	}
	if network == ipProtoUDP {
		return tun.FlowVerdict{Action: tun.ActionDrop}
	}
	return tun.FlowVerdict{Action: tun.ActionAccept}
}

// What the stack means by "network" here is the IP protocol number - 6 and
// 17 - and not sing's "tcp"/"udp" strings. sing-tun calls this with
// uint8(header.TCPProtocolNumber) and uint8(header.UDPProtocolNumber).
//
// Worth a name, because the mistake it replaces compiled and read correctly.
// N.NetworkUDP is the string "udp", so N.NetworkUDP[0] is the character 'u',
// 117, which is a number no protocol is - and the test against it was never
// once true. Every UDP flow the comment above says is dropped was quietly
// accepted instead, dialled as though it were a stream, and closed a layer
// further in when CONNECT would not carry it. What a person saw was the
// same. Where it happened, and how long it took to happen, was not.
//
// Left untyped, because the two places that want them want different types:
// JudgeFlow compares against a uint8 and whoOpened takes an int.
const (
	ipProtoTCP = 6
	ipProtoUDP = 17
)

// label is what to call this destination in the list.
//
// The name it was looked up under if this app answered that lookup, and the
// address otherwise - which is what an application that dialled an address
// directly, or resolved it before the tunnel came up, deserves to be shown
// as. Never a guess: an unlabelled row is a fact, and a mislabelled one is
// not.
func (h *handler) label(destination M.Socksaddr) string {
	if !destination.IsIP() {
		// Already a name. Nothing on this path produces one today, but a
		// caller that did should not have it thrown away.
		return destination.String()
	}
	name := h.names.Name(destination.Addr.Unmap())
	if name == "" {
		return ""
	}
	// With the port, because the address had one and a row that lost it
	// would merge a site with its own API on a different port.
	return net.JoinHostPort(name, strconv.Itoa(int(destination.Port)))
}

// NewConnectionEx is one TCP connection from an app on the phone.
func (h *handler) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	go func() {
		err := h.carry(ctx, conn, source, destination)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}()
}

func (h *handler) carry(ctx context.Context, conn net.Conn, source, destination M.Socksaddr) error {
	// Counted before the dial, so a destination that refuses still appears in
	// the list. "It tried and failed" is the answer somebody opens that sheet
	// looking for at least as often as "it worked".
	// The five-tuple the platform needs to name the owner. Asked once per
	// connection, before the dial, because after it the source port may
	// already be gone from the kernel's table.
	//
	// Nothing but TCP reaches here, because JudgeFlow drops UDP and hijacks
	// DNS before either gets this far - so the protocol to look the owner up
	// under is a constant, and it is named rather than written as 6.
	row := h.hosts.opened(destination.String(), h.label(destination),
		whoOpened(ipProtoTCP, source.String(), destination.String()))

	upstream, spare, err := (*Tunnel)(h).open(destination.String(), 15*time.Second)
	if err != nil {
		h.hosts.closed(row)
		return err
	}
	defer upstream.Close()

	// Anything the exit sent behind its 200 belongs to this connection. It
	// has never been seen from these exits, but dropping it silently would be
	// a corruption that only shows up as a page that half loads.
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			h.hosts.closed(row)
			return err
		}
		h.hosts.moved(row, 0, int64(len(spare)))
	}

	// Counted as the bytes pass, through a writer that adds each chunk to
	// the row - so the meter moves during a download, not after it.
	toExit := counted{w: upstream, h: h.hosts, row: row}
	toApp := counted{w: conn, h: h.hosts, row: row, down: true}
	done := make(chan error, 2)
	go func() {
		// The first thing a client sends is where its name is, if it has
		// one. Read once, before the copying starts, and then handed on
		// unchanged - so the peek costs one small read and nothing after it.
		//
		// Only the client-to-exit direction, and only the first message.
		// Everything else goes straight through io.Copy as before.
		if first, err := firstBytes(conn); err == nil && len(first) > 0 {
			if name := sniFrom(first); name != "" {
				h.hosts.rename(destination.String(),
					net.JoinHostPort(name, strconv.Itoa(int(destination.Port))))
			}
			if _, werr := toExit.Write(first); werr != nil {
				done <- werr
				return
			}
		}
		_, err := io.Copy(toExit, conn)
		done <- err
	}()
	go func() { _, err := io.Copy(toApp, upstream); done <- err }()

	// Both halves finish before the row is marked closed, so it is never
	// shown as idle while one copier is still adding to it. Closing upstream
	// is what ends the other copy, so waiting for it is bounded: one read
	// that is already failing.
	var first error
	select {
	case first = <-done:
	case <-ctx.Done():
		first = ctx.Err()
	}
	upstream.Close()
	conn.Close()
	<-done

	h.hosts.closed(row)
	return first
}

// NewPacketConnectionEx is UDP that got past JudgeFlow, which is none of it.
// Closed rather than left hanging, so an app that tries gets an answer.
func (h *handler) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _ M.Socksaddr, _ M.Socksaddr, onClose N.CloseHandlerFunc) {
	conn.Close()
	if onClose != nil {
		onClose(errors.New("this exit is an HTTPS proxy, and CONNECT carries no UDP"))
	}
}

// NewDNSPacket re-asks one DNS query over HTTPS, through the exit.
//
// The wire format is passed straight through - RFC 8484 POST takes the same
// bytes the phone put on the wire and gives back the same bytes it expects,
// so nothing here has to know what a DNS record is.
func (h *handler) NewDNSPacket(payload []byte, _ M.Socksaddr, destination M.Socksaddr, writer N.PacketWriter) {
	query := make([]byte, len(payload))
	copy(query, payload)

	go func() {
		answer, err := h.askDNS(query)
		if err != nil || len(answer) == 0 {
			// Nothing written. The phone's resolver retries and then gives
			// up, which is the same thing it does for a dropped packet -
			// and inventing a SERVFAIL here would have applications cache
			// the failure.
			//
			// Said out loud all the same. Silence towards the phone is
			// right; silence everywhere is how "no name resolves, but every
			// address does" becomes an afternoon. From outside, a resolver
			// that answered nothing and a packet that never arrived look
			// identical, and this is the only place that knows which of the
			// two it was. gomobile puts Go's stderr on logcat, so it lands
			// beside the service's own lines.
			if err != nil {
				log.Printf("relay: dns through the exit: %v", err)
			} else {
				log.Printf("relay: dns through the exit: empty answer")
			}
			return
		}
		// Read on the way past, not on the way in: an answer is what maps a
		// name to an address, and this is the only place on the phone that
		// sees both halves.
		h.names.learn(answer)
		if err := writePacket(writer, answer, destination); err != nil {
			log.Printf("relay: dns answer not written back: %v", err)
		}
	}()
}

func (h *handler) askDNS(query []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, dnsThroughTunnel,
		bytesReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")

	resp, err := h.dns.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("the resolver answered " + resp.Status)
	}
	// A DNS answer over UDP cannot exceed 64k, and one that claims to is not
	// an answer this is going to write back onto a tun.
	return io.ReadAll(io.LimitReader(resp.Body, 65535))
}
