package com.erelay.relay

/**
 * Which addresses go into the tunnel, when some are to be left out of it.
 *
 * `addRoute("0.0.0.0", 0)` sends everything, which is the safe default and
 * also sends the printer, the NAS, the router's own page and - the way this
 * was noticed - adb over wifi. A phone being debugged wirelessly loses its
 * debugger the moment the tunnel comes up, because the debugger is on the
 * same LAN and the LAN is now on the far side of Belgium.
 *
 * Android 13 added `Builder.excludeRoute`, which says this in one line. Below
 * that there is no way to subtract a route, only to add ones - so the tunnel
 * has to be handed the whole of 0.0.0.0/0 *minus* the private ranges, spelled
 * out as the couple of dozen prefixes that covers.
 *
 * That list is computed here rather than pasted from somewhere, because a
 * pasted list is a list nobody can check: one wrong prefix and either a
 * chunk of the internet quietly leaves the tunnel, or a chunk of the LAN
 * quietly enters it, and neither announces itself.
 */
object Routes {

    /** A network, as an address and a prefix length. */
    data class Prefix(val addr: String, val bits: Int)

    /**
     * The ranges a "local network" means.
     *
     * The three RFC 1918 blocks, plus link-local - which is what a device
     * with no DHCP gives itself, and what some printers and cameras live on
     * permanently. Loopback is not here: it never reaches an interface.
     */
    val PRIVATE = listOf(
        Prefix("10.0.0.0", 8),
        Prefix("172.16.0.0", 12),
        Prefix("192.168.0.0", 16),
        Prefix("169.254.0.0", 16),
    )

    private fun toLong(addr: String): Long =
        addr.split(".").fold(0L) { acc, part -> (acc shl 8) or part.toLong() }

    private fun toAddr(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private fun maskFor(bits: Int): Long =
        if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL

    private fun contains(outer: Prefix, inner: Prefix): Boolean {
        if (outer.bits > inner.bits) return false
        val m = maskFor(outer.bits)
        return (toLong(outer.addr) and m) == (toLong(inner.addr) and m)
    }

    private fun overlaps(a: Prefix, b: Prefix): Boolean = contains(a, b) || contains(b, a)

    /**
     * 0.0.0.0/0 with [excluded] taken out of it.
     *
     * Walk down the bits of each excluded prefix; at every step the sibling
     * of the path is a whole subtree that cannot contain it, so the sibling
     * goes in the answer. Doing that for one prefix gives its exact
     * complement. Doing it for several gives a superset, because one
     * exclusion's siblings can still cover another exclusion - so anything
     * overlapping any exclusion is dropped afterwards.
     */
    fun allExcept(excluded: List<Prefix>): List<Prefix> {
        val out = LinkedHashSet<Prefix>()

        for (e in excluded) {
            val v = toLong(e.addr)
            for (i in 0 until e.bits) {
                // The sibling at depth i+1: the same path with bit i flipped.
                val flipped = v xor (1L shl (31 - i))
                val bits = i + 1
                out.add(Prefix(toAddr(flipped and maskFor(bits)), bits))
            }
        }

        return out.filterNot { candidate ->
            excluded.any { overlaps(it, candidate) }
        }.sortedWith(compareBy({ it.bits }, { toLong(it.addr) }))
    }

    /** Everything except the local network. */
    fun exceptPrivate(): List<Prefix> = allExcept(PRIVATE)
}
