package core

import "encoding/binary"

// The name a TLS client is asking for, read out of its first message.
//
// This exists because the obvious approach does not work on a modern phone.
// Remembering DNS answers is right in principle and useless in practice: this
// phone resolves over DNS-over-TLS, so its lookups leave as an ordinary
// encrypted connection to 1.1.1.1:853 and nothing on this side ever sees an
// answer. The log sheet showed 140.82.121.3 for github.com and always would
// have.
//
// A ClientHello is different. It is the first thing any TLS client sends, it
// is not encrypted, and it carries the name in the server_name extension -
// which is what makes SNI worth stripping in exit.go and worth reading here.
// It works whatever the application did about DNS, including nothing.
//
// Read-only and best-effort. A message this does not understand is a row
// labelled by address, which is what the row would have been anyway.
func sniFrom(b []byte) string {
	// TLSPlaintext: type(1) version(2) length(2), then the handshake.
	if len(b) < 43 || b[0] != 0x16 {
		return ""
	}
	// Handshake: type(1) length(3) version(2) random(32) ...
	if b[5] != 0x01 {
		return ""
	}

	pos := 43 // through the random
	if pos >= len(b) {
		return ""
	}

	// session_id
	n := int(b[pos])
	pos += 1 + n
	if pos+2 > len(b) {
		return ""
	}

	// cipher_suites
	n = int(binary.BigEndian.Uint16(b[pos : pos+2]))
	pos += 2 + n
	if pos+1 > len(b) {
		return ""
	}

	// compression_methods
	n = int(b[pos])
	pos += 1 + n
	if pos+2 > len(b) {
		return ""
	}

	// extensions
	end := pos + 2 + int(binary.BigEndian.Uint16(b[pos:pos+2]))
	pos += 2
	if end > len(b) {
		end = len(b)
	}

	for pos+4 <= end {
		kind := binary.BigEndian.Uint16(b[pos : pos+2])
		size := int(binary.BigEndian.Uint16(b[pos+2 : pos+4]))
		pos += 4
		if pos+size > end {
			return ""
		}
		if kind != 0 { // server_name
			pos += size
			continue
		}

		// ServerNameList: list length(2), then entries of type(1) length(2).
		ext := b[pos : pos+size]
		if len(ext) < 5 {
			return ""
		}
		at := 2
		for at+3 <= len(ext) {
			nameType := ext[at]
			nameLen := int(binary.BigEndian.Uint16(ext[at+1 : at+3]))
			at += 3
			if at+nameLen > len(ext) {
				return ""
			}
			if nameType == 0 { // host_name
				return string(ext[at : at+nameLen])
			}
			at += nameLen
		}
		return ""
	}
	return ""
}
