//go:build android || linux || darwin

package core

import (
	"net/netip"
	"testing"

	tun "github.com/sagernet/sing-tun"
)

// JudgeFlow is asked with an IP protocol number, and the bug this pins was
// that the number was compared against a string's first byte.
//
// N.NetworkUDP is "udp", so N.NetworkUDP[0] is 'u' - 117 - and no protocol
// number is 117. The comparison was never once true, so every UDP flow the
// tunnel means to drop was accepted instead, dialled as though CONNECT could
// carry it, and failed a layer further in. Nothing a person saw changed,
// which is why it survived: only where the failure happened moved.
//
// The numbers are the ones sing-tun passes: it calls this with
// uint8(header.TCPProtocolNumber) and uint8(header.UDPProtocolNumber), 6 and
// 17. Asserted here rather than read from gvisor, which is an indirect
// dependency and not worth making a direct one for two integers.
func TestJudgeFlow(t *testing.T) {
	if ipProtoTCP != 6 || ipProtoUDP != 17 {
		t.Fatalf("the stack passes 6 and 17; this file says %d and %d",
			ipProtoTCP, ipProtoUDP)
	}

	h := (*handler)(&Tunnel{})
	web := netip.MustParseAddrPort("93.184.216.34:443")

	for _, c := range []struct {
		what        string
		protocol    uint8
		destination netip.AddrPort
		want        tun.FlowAction
	}{
		{"tcp to the web is carried", ipProtoTCP, web, tun.ActionAccept},
		{"udp is dropped", ipProtoUDP, web, tun.ActionDrop},
		{"dns over tcp is hijacked", ipProtoTCP,
			netip.MustParseAddrPort("1.1.1.1:53"), tun.ActionHijackDNS},
		{"dns over udp is hijacked, not dropped", ipProtoUDP,
			netip.MustParseAddrPort("8.8.8.8:53"), tun.ActionHijackDNS},
		// The value the old comparison was reachable only by. Nothing sends
		// it, and a flow carrying it is not a reason to drop anything.
		{"'u' is not a protocol", 'u', web, tun.ActionAccept},
	} {
		got := h.JudgeFlow(c.protocol, netip.AddrPort{}, c.destination, nil).Action
		if got != c.want {
			t.Errorf("%s: protocol %d to %s gave %v, wanted %v",
				c.what, c.protocol, c.destination, got, c.want)
		}
	}
}
