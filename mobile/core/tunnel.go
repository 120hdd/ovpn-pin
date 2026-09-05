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
	exit  Way
	stack tun.Stack
	dev   tun.Tun
	dns   *http.Client
	hosts *Hosts
	names *dnsNames

	closeOnce sync.Once
}

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

	t := &Tunnel{exit: exit, hosts: newHosts(), names: newDNSNames()}
	t.dns = &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			// Every DNS query is its own tunnel through the exit, and then
			// its own TLS inside that - which is what a proxied HTTPS request
			// is. The outer handshake announces nothing; the inner one names
			// 1.1.1.1, which is an address and so is not announced either.
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				conn, spare, err := exit.Open(addr, 10*time.Second)
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
	switch w := t.exit.(type) {
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
	if network == N.NetworkUDP[0] || destination.Port() == 53 {
		if destination.Port() == 53 {
			return tun.FlowVerdict{Action: tun.ActionHijackDNS}
		}
		return tun.FlowVerdict{Action: tun.ActionDrop}
	}
	return tun.FlowVerdict{Action: tun.ActionAccept}
}

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
	// 6 is IPPROTO_TCP; nothing else reaches here, because JudgeFlow drops
	// UDP and hijacks DNS before either gets this far.
	row := h.hosts.opened(destination.String(), h.label(destination),
		whoOpened(6, source.String(), destination.String()))

	upstream, spare, err := h.exit.Open(destination.String(), 15*time.Second)
	if err != nil {
		h.hosts.closed(row, 0, 0)
		return err
	}
	defer upstream.Close()

	// Anything the exit sent behind its 200 belongs to this connection. It
	// has never been seen from these exits, but dropping it silently would be
	// a corruption that only shows up as a page that half loads.
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			return err
		}
	}

	// io.Copy already returns what it moved, so the counting is free - no
	// wrapper reader, no extra allocation per chunk, nothing in the path of
	// the bytes themselves.
	var up, down atomic.Int64
	done := make(chan error, 2)
	go func() {
		// The first thing a client sends is where its name is, if it has
		// one. Read once, before the copying starts, and then handed on
		// unchanged - so the peek costs one small read and nothing after it.
		//
		// Only the client-to-exit direction, and only the first message.
		// Everything else goes straight through io.Copy as before.
		var moved int64
		if first, err := firstBytes(conn); err == nil && len(first) > 0 {
			if name := sniFrom(first); name != "" {
				h.hosts.rename(destination.String(),
					net.JoinHostPort(name, strconv.Itoa(int(destination.Port))))
			}
			n, werr := upstream.Write(first)
			moved += int64(n)
			if werr != nil {
				up.Store(moved)
				done <- werr
				return
			}
		}
		n, err := io.Copy(upstream, conn)
		up.Store(moved + n)
		done <- err
	}()
	go func() { n, err := io.Copy(conn, upstream); down.Store(n); done <- err }()

	// Both halves have to finish before the counts are read, and that is not
	// fussiness - it was wrong. select returns on whichever copier ends
	// first, and the deferred record then read a counter the other one had
	// not written yet. In practice the client closes first, so the upload was
	// counted and the download was always zero:
	//
	//	149.154.167.91:5222   down 0 B   up 2.33 KB
	//
	// Closing upstream is what ends the other copy, so waiting for it is
	// bounded: one read that is already failing.
	var first error
	select {
	case first = <-done:
	case <-ctx.Done():
		first = ctx.Err()
	}
	upstream.Close()
	conn.Close()
	<-done

	h.hosts.closed(row, up.Load(), down.Load())
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
func (h *handler) NewDNSPacket(payload []byte, _ M.Socksaddr, _ M.Socksaddr, writer N.PacketWriter) {
	query := make([]byte, len(payload))
	copy(query, payload)

	go func() {
		answer, err := h.askDNS(query)
		if err != nil || len(answer) == 0 {
			// Nothing written. The phone's resolver retries and then gives
			// up, which is the same thing it does for a dropped packet -
			// and inventing a SERVFAIL here would have applications cache
			// the failure.
			return
		}
		// Read on the way past, not on the way in: an answer is what maps a
		// name to an address, and this is the only place on the phone that
		// sees both halves.
		h.names.learn(answer)
		_ = writePacket(writer, answer)
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
