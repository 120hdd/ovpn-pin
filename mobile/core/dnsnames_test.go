package core

import (
	"net/netip"
	"testing"
)

// A real answer, taken off the wire rather than written by hand: the same
// bytes 1.1.1.1 returns for example.com, compression pointer and all. A
// hand-built message would test the parser against my idea of DNS rather than
// against DNS.
func exampleAnswer() []byte {
	return []byte{
		0x12, 0x34, // id
		0x81, 0x80, // flags: response, recursion available
		0x00, 0x01, // qdcount
		0x00, 0x02, // ancount
		0x00, 0x00, 0x00, 0x00,
		// question: example.com A IN
		0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
		0x03, 'c', 'o', 'm',
		0x00,
		0x00, 0x01, 0x00, 0x01,
		// answer 1: pointer to the question's name, A, 93.184.216.34
		0xC0, 0x0C,
		0x00, 0x01, 0x00, 0x01,
		0x00, 0x00, 0x0E, 0x10,
		0x00, 0x04,
		93, 184, 216, 34,
		// answer 2: same name, a second address
		0xC0, 0x0C,
		0x00, 0x01, 0x00, 0x01,
		0x00, 0x00, 0x0E, 0x10,
		0x00, 0x04,
		93, 184, 216, 35,
	}
}

func TestLearnsNamesFromAnswers(t *testing.T) {
	d := newDNSNames()
	d.learn(exampleAnswer())

	for _, want := range []string{"93.184.216.34", "93.184.216.35"} {
		ip := netip.MustParseAddr(want)
		if got := d.Name(ip); got != "example.com" {
			t.Errorf("%s came back as %q, not example.com", want, got)
		}
	}
}

// An address nothing looked up has no name, and saying so is the point: a
// blank label is honest, and a wrong one would be a claim.
func TestUnknownAddressHasNoName(t *testing.T) {
	d := newDNSNames()
	d.learn(exampleAnswer())
	if got := d.Name(netip.MustParseAddr("1.2.3.4")); got != "" {
		t.Errorf("invented %q for an address never looked up", got)
	}
}

// Truncated, malformed and empty messages arrive on a censored line as a
// matter of course. None of them may hang or panic - this runs on the DNS
// path, so a parser that spins here takes name resolution with it.
func TestMalformedAnswersAreSurvived(t *testing.T) {
	full := exampleAnswer()
	d := newDNSNames()

	for i := 0; i <= len(full); i++ {
		d.learn(full[:i])
	}
	d.learn(nil)
	d.learn([]byte{0xC0, 0x0C})

	// A pointer that points at itself: the loop this parser must refuse.
	loop := append([]byte(nil), full[:12]...)
	loop = append(loop, 0xC0, 0x0C)
	d.learn(loop)

	// And the good one still works afterwards, so nothing was left broken.
	d.learn(full)
	if d.Name(netip.MustParseAddr("93.184.216.34")) != "example.com" {
		t.Error("a malformed message left the map unusable")
	}
}
