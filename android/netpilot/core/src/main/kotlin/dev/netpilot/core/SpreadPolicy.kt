package dev.netpilot.core

/**
 * Chooses which network a new connection uses when Wi-Fi and mobile data are used together.
 *
 * Each *destination* (server address) is assigned to one network and stays there for
 * [stickyMs] after its last use. Sites that tie a login to the client's IP address keep
 * working, and a single connection is never split (that would need a server in the middle).
 * New destinations are shared out by smooth weighted round-robin, so a network with twice
 * the weight gets about twice the destinations.
 *
 * Thread-safe.
 */
class SpreadPolicy(
    private val stickyMs: Long = 10 * 60_000L,
    private val maxSticky: Int = 4_096,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Candidate(val id: String, val weight: Int)

    private class Assignment(val id: String, var lastUsed: Long)

    private val sticky = object : LinkedHashMap<String, Assignment>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Assignment>?) = size > maxSticky
    }
    private val current = HashMap<String, Long>()

    /**
     * Network id for a new connection to [destination], or null if there is no candidate.
     * [exclude] is a network that just failed for this destination; the choice moves elsewhere.
     * With [sticky] false every connection is shared out on its own, even to the same server:
     * faster for multi-connection downloads and speed tests, but a site that ties a login to
     * the client's IP address may sign the user out.
     */
    @Synchronized
    fun pick(destination: String, candidates: List<Candidate>, exclude: String? = null, sticky: Boolean = true): String? {
        val usable = candidates.filter { it.weight > 0 && it.id != exclude }
        if (usable.isEmpty()) return null
        val now = clock()
        if (sticky) this.sticky[destination]?.let { a ->
            if (now - a.lastUsed < stickyMs && usable.any { it.id == a.id }) { a.lastUsed = now; return a.id }
        }
        val total = usable.sumOf { it.weight.toLong() }
        var best: Candidate? = null
        var bestScore = Long.MIN_VALUE
        for (c in usable) {
            val score = (current[c.id] ?: 0L) + c.weight
            current[c.id] = score
            if (score > bestScore) { bestScore = score; best = c }
        }
        val chosen = best!!
        current[chosen.id] = bestScore - total
        current.keys.retainAll(usable.map { it.id }.toSet())
        if (sticky) this.sticky[destination] = Assignment(chosen.id, now)
        return chosen.id
    }

    @Synchronized
    fun clear() { sticky.clear(); current.clear() }

    companion object {
        /**
         * Relative weight from a measured or estimated download speed in Mbps: proportional,
         * but never zero for a working network and capped so one figure can't dominate forever.
         */
        fun weightFor(mbps: Double?): Int = when {
            mbps == null || mbps.isNaN() -> 10
            else -> mbps.coerceIn(1.0, 1_000.0).toInt()
        }
    }
}
