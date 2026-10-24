package dev.netpilot.core

/** An IPv4 prefix such as 10.0.0.0/8, as a start address and length. */
data class Cidr4(val address: Long, val prefix: Int) {
    val size: Long get() = 1L shl (32 - prefix)
    val end: Long get() = address + size - 1
    override fun toString() = listOf(24, 16, 8, 0).joinToString(".") { ((address shr it) and 0xFF).toString() } + "/$prefix"

    companion object {
        fun parse(s: String): Cidr4 {
            val (ip, len) = s.split('/')
            val a = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
            return Cidr4(a, len.toInt())
        }
    }
}

/** Which addresses the "use both networks" VPN captures. */
object Routes {
    /**
     * Not captured: private networks (printers, TVs, routers), loopback, link-local,
     * multicast and broadcast. They keep going straight to Wi-Fi, so casting, printing
     * and device discovery work as before, and mobile data is never tried for them.
     */
    val LOCAL_V4 = listOf("0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4").map(Cidr4::parse)

    /** IPv6 global unicast only (2000::/3): leaves out link-local, unique-local and multicast. */
    const val PUBLIC_V6 = "2000::"
    const val PUBLIC_V6_PREFIX = 3

    /** The smallest set of prefixes covering all IPv4 addresses except [excluded]. */
    fun publicV4(excluded: List<Cidr4> = LOCAL_V4): List<Cidr4> {
        val out = ArrayList<Cidr4>()
        var start = 0L
        for (ex in excluded.sortedBy { it.address }) {
            if (ex.address > start) cover(start, ex.address - 1, out)
            start = maxOf(start, ex.end + 1)
        }
        if (start <= 0xFFFFFFFFL) cover(start, 0xFFFFFFFFL, out)
        return out
    }

    private fun cover(from: Long, to: Long, out: MutableList<Cidr4>) {
        var a = from
        while (a <= to) {
            var prefix = 32
            // Grow the block while it stays aligned and inside the range.
            while (prefix > 0) {
                val bigger = 1L shl (33 - prefix)
                if (a % bigger != 0L || a + bigger - 1 > to) break
                prefix--
            }
            out += Cidr4(a, prefix)
            a += 1L shl (32 - prefix)
        }
    }
}
