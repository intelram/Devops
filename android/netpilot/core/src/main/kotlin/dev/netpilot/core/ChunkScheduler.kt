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
 * Thread-safe: workers on different networks call it concurrently.
 */
class ChunkScheduler(
    val totalBytes: Long,
    private val chunkBytes: Long = 1L shl 20,
) {
    init {
        require(totalBytes > 0) { "totalBytes must be > 0" }
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
    }

    private val lock = Any()
    private val pending = ArrayDeque<ByteRange>()
    private val inFlight = HashMap<ByteRange, String>()
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
        pending.removeFirstOrNull()?.also { inFlight[it] = linkId }
    }

    fun complete(range: ByteRange) = synchronized(lock) {
        val link = inFlight.remove(range) ?: return
        completedBytes += range.length
        bytesByLink[link] = (bytesByLink[link] ?: 0) + range.length
    }

    /** Return a range after a failed request so another link can take it. */
    fun fail(range: ByteRange) = synchronized(lock) {
        if (inFlight.remove(range) != null) pending.addFirst(range)
    }

    /** Requeue everything a lost network was holding. */
    fun failAll(linkId: String) = synchronized(lock) {
        val lost = inFlight.filterValues { it == linkId }.keys
        lost.forEach { inFlight.remove(it); pending.addFirst(it) }
    }

    val isDone: Boolean get() = synchronized(lock) { completedBytes == totalBytes }
    val hasWorkLeft: Boolean get() = synchronized(lock) { pending.isNotEmpty() || inFlight.isNotEmpty() }
    fun completed(): Long = synchronized(lock) { completedBytes }
    fun bytesPerLink(): Map<String, Long> = synchronized(lock) { HashMap(bytesByLink) }
}
