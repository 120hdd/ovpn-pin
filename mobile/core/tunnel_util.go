//go:build android || linux || darwin

package core

import (
	"bytes"
	"io"
	"net"
	"time"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func bytesReader(b []byte) io.Reader { return bytes.NewReader(b) }

// writePacket hands a DNS answer back to the stack in the buffer type it
// wants, rather than making the caller know about buf.Buffer.
func writePacket(writer N.PacketWriter, payload []byte) error {
	return writer.WritePacket(buf.As(payload), M.Socksaddr{})
}

// firstBytes is one read of whatever a client says first.
//
// Bounded twice, because this sits in front of every connection the phone
// makes. A protocol where the server speaks first - SMTP, some databases -
// would otherwise wait here for as long as the deadline allows before a byte
// moved in either direction, so the deadline is short and a timeout is not an
// error: it means there was nothing to read, which is a fine answer.
//
// 4096 because a ClientHello fits in one TLS record and a record is capped
// below that. A message longer than this is a message this cannot name
// anyway.
func firstBytes(conn net.Conn) ([]byte, error) {
	_ = conn.SetReadDeadline(time.Now().Add(400 * time.Millisecond))
	defer conn.SetReadDeadline(time.Time{})

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if n > 0 {
		// A read that timed out having returned bytes is a read, not a
		// failure - the caller wants what arrived.
		return buf[:n], nil
	}
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		return nil, nil
	}
	return nil, err
}
