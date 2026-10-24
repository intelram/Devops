package dev.netpilot.core

/** Inclusive byte range, as in an HTTP `Range: bytes=start-end` header. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    init { require(start in 0..endInclusive) { "bad range $start-$endInclusive" } }
    val length: Long get() = endInclusive - start + 1
    fun header(): String = "bytes=$start-$endInclusive"
}

/**
 * Work queue for the in-app multipath downloader. Each network runs one or more
 * workers that pull fixed-size chunks; faster links pull more often, so load is
 * proportional to throughput without having to measure it up front.
 *
 * End-game mode: once every range has been handed out, an idle link gets a copy of the
 * oldest range still in flight on another link, and whichever copy finishes first counts.
 * Without it, the last chunk stuck on a slow link (e.g. weak mobile data) can make the
 * "combined" download finish later than the fast link alone would have. At most one extra
 * copy per range, so the waste is bounded by one chunk per link.
 *
 * Thread-safe: workers on different networks call it concurrently.
 */
class ChunkScheduler(
    val totalBytes: Long,
    private val chunkBytes: Long = 1L shl 20,
    private val endGame: Boolean = true,
) {
    init {
        require(totalBytes > 0) { "totalBytes must be > 0" }
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
    }

    private val lock = Any()
    private val pending = ArrayDeque<ByteRange>()
    /** Range -> links currently fetching it (two only in end-game). Insertion order = age. */
    private val inFlight = LinkedHashMap<ByteRange, MutableSet<String>>()
    private val bytesByLink = HashMap<String, Long>()
    private var completedBytes = 0L

    init {
        var s = 0L
        while (s < totalBytes) {
            val e = minOf(s + chunkBytes, totalBytes) - 1
            pending.addLast(ByteRange(s, e))
            s = e + 1
        }
    }

    /** Next range for [linkId], or null when nothing is left to hand out. */
    fun next(linkId: String): ByteRange? = synchronized(lock) {
        pending.removeFirstOrNull()?.let { inFlight[it] = mutableSetOf(linkId); return it }
        if (!endGame) return null
        // End-game: duplicate the oldest range another link is still working on.
        val (range, owners) = inFlight.entries.firstOrNull { (_, o) -> o.size == 1 && linkId !in o } ?: return null
        owners += linkId
        range
    }

    /** First copy to finish counts; a late duplicate is ignored. */
    fun complete(range: ByteRange, linkId: String) = synchronized(lock) {
        inFlight.remove(range) ?: return
        completedBytes += range.length
        bytesByLink[linkId] = (bytesByLink[linkId] ?: 0) + range.length
    }

    /** A link failed this range; requeue it unless another link is still fetching it. */
    fun fail(range: ByteRange, linkId: String) = synchronized(lock) {
        val owners = inFlight[range] ?: return
        owners -= linkId
        if (owners.isEmpty()) { inFlight.remove(range); pending.addFirst(range) }
    }

    /** Requeue everything a lost network was holding (unless a duplicate is still running elsewhere). */
    fun failAll(linkId: String) = synchronized(lock) {
        inFlight.filterValues { linkId in it }.keys.toList().forEach { fail(it, linkId) }
    }

    val isDone: Boolean get() = synchronized(lock) { completedBytes == totalBytes }
    val hasWorkLeft: Boolean get() = synchronized(lock) { pending.isNotEmpty() || inFlight.isNotEmpty() }
    fun completed(): Long = synchronized(lock) { completedBytes }
    fun bytesPerLink(): Map<String, Long> = synchronized(lock) { HashMap(bytesByLink) }
}
