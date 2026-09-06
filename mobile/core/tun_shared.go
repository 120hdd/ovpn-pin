package core

import "net/netip"

// The addresses the tun itself answers on. Out here rather than beside the
// tunnel because the Kotlin that configures VpnService.Builder reads them,
// and it has to be able to read them on a build where the tunnel itself is
// not compiled - so that the two ends agree by construction rather than by
// two people remembering the same number.
//
// A /30 on purpose: the tun holds exactly one usable peer, and a wider prefix
// would have the stack answer for addresses nothing is at.
var (
	TunAddress = netip.MustParsePrefix("172.19.0.1/30")

	// Large, and deliberately not the 1500 an ethernet would use. Every
	// packet the phone writes is reassembled into a stream and re-sent over
	// one TCP connection to the exit, so the tun's MTU is not carrying
	// anything onto a wire - it only decides how much the stack is handed at
	// a time. Bigger means fewer trips across the tun for the same bytes.
	TunMTU = 9000
)
