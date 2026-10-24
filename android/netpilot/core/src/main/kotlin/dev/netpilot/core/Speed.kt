package dev.netpilot.core

/**
 * Download/upload rate from a running byte counter. The first [warmupMs] are ignored: TCP
 * starts slowly, and counting that ramp would understate what the connection can do.
 */
class ThroughputMeter(private val warmupMs: Long = 1_000) {
    private var first = -1L
    private var fromTime = -1L
    private var fromBytes = 0L
    private var lastTime = -1L
    private var lastBytes = 0L

    @Synchronized
    fun sample(nowMs: Long, totalBytes: Long) {
        if (first < 0) first = nowMs
        if (nowMs - first <= warmupMs) { fromTime = nowMs; fromBytes = totalBytes }
        lastTime = nowMs; lastBytes = totalBytes
    }

    /** Megabits per second since the warm-up ended, or null if there isn't a measurable interval yet. */
    @Synchronized
    fun mbps(): Double? {
        val ms = lastTime - fromTime
        if (fromTime < 0 || ms < 200) return null
        return (lastBytes - fromBytes) * 8.0 / 1_000.0 / ms
    }
}

data class LinkSpeed(val downMbps: Double?, val upMbps: Double?, val pingMs: Int?)

/** One speed test: each network alone, and download on both at once. */
data class SpeedResult(val time: Long, val wifi: LinkSpeed?, val cell: LinkSpeed?, val bothDownMbps: Double?) {

    fun encode(): String = listOf(time, wifi?.downMbps, wifi?.upMbps, wifi?.pingMs, wifi != null,
        cell?.downMbps, cell?.upMbps, cell?.pingMs, cell != null, bothDownMbps).joinToString(",") { it?.toString() ?: "" }

    companion object {
        fun decode(s: String): SpeedResult? = runCatching {
            val f = s.split(',')
            if (f.size != 10) return null
            fun d(i: Int) = f[i].takeIf { it.isNotEmpty() }?.toDouble()
            fun n(i: Int) = f[i].takeIf { it.isNotEmpty() }?.toInt()
            SpeedResult(f[0].toLong(),
                if (f[4] == "true") LinkSpeed(d(1), d(2), n(3)) else null,
                if (f[8] == "true") LinkSpeed(d(5), d(6), n(7)) else null,
                d(9))
        }.getOrNull()

        fun median(values: List<Int>): Int? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }
}
