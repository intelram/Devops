package dev.netpilot.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.netpilot.core.LinkSpeed
import dev.netpilot.core.SpeedResult
import dev.netpilot.core.ThroughputMeter
import dev.netpilot.core.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Measures each network on its own, then download on both at once. Uses Cloudflare's public
 * speed-test endpoints over HTTPS (they see the phone's IP address, like any website).
 *
 * Mobile data is capped per step ([CELL_DOWN_CAP], [CELL_UP_CAP]), so a full run uses at most
 * about 100 MB of it.
 */
class SpeedTester(private val cm: ConnectivityManager) {

    enum class Step { PING, DOWNLOAD, UPLOAD, BOTH }

    /** [mbps] is the live figure for the step in progress. */
    class Progress(val transport: Transport?, val step: Step, val mbps: Double?)

    suspend fun run(onProgress: (Progress) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        val wifi = find(NetworkCapabilities.TRANSPORT_WIFI)
        val cell = find(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (wifi == null && cell == null) throw IOException("no working Wi-Fi or mobile data")
        val w = wifi?.let { measure(it, Transport.WIFI, onProgress) }
        val c = cell?.let { measure(it, Transport.CELLULAR, onProgress) }
        val both = if (wifi != null && cell != null) {
            onProgress(Progress(null, Step.BOTH, null))
            val wm = ThroughputMeter(); val cm2 = ThroughputMeter()
            coroutineScope {
                val a = async { download(wifi, DOWN_SECONDS, Long.MAX_VALUE, wm) { onProgress(Progress(null, Step.BOTH, sum(wm, cm2))) } }
                val b = async { download(cell, DOWN_SECONDS, CELL_DOWN_CAP, cm2) { onProgress(Progress(null, Step.BOTH, sum(wm, cm2))) } }
                a.await(); b.await()
            }
            sum(wm, cm2)
        } else null
        SpeedResult(System.currentTimeMillis(), w, c, both)
    }

    private fun sum(a: ThroughputMeter, b: ThroughputMeter): Double? {
        val x = a.mbps(); val y = b.mbps()
        return if (x == null && y == null) null else (x ?: 0.0) + (y ?: 0.0)
    }

    /** A working network of this kind; one Android hasn't validated only if there is no validated one. */
    private fun find(transport: Int): Network? {
        @Suppress("DEPRECATION")
        val all = cm.allNetworks.filter { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(transport) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return all.firstOrNull { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
            ?: all.firstOrNull()
    }

    private suspend fun measure(n: Network, t: Transport, onProgress: (Progress) -> Unit): LinkSpeed {
        onProgress(Progress(t, Step.PING, null))
        val ping = runCatching { ping(n) }.getOrNull()
        val down = ThroughputMeter()
        onProgress(Progress(t, Step.DOWNLOAD, null))
        runCatching { download(n, DOWN_SECONDS, if (t == Transport.CELLULAR) CELL_DOWN_CAP else Long.MAX_VALUE, down) { onProgress(Progress(t, Step.DOWNLOAD, down.mbps())) } }
        val up = ThroughputMeter()
        onProgress(Progress(t, Step.UPLOAD, null))
        runCatching { upload(n, UP_SECONDS, if (t == Transport.CELLULAR) CELL_UP_CAP else Long.MAX_VALUE, up) { onProgress(Progress(t, Step.UPLOAD, up.mbps())) } }
        return LinkSpeed(down.mbps(), up.mbps(), ping)
    }

    /** Median time for a tiny request on a warm connection: the round trip apps feel. */
    private fun ping(n: Network): Int? {
        val times = ArrayList<Int>()
        repeat(6) { i ->
            val start = System.nanoTime()
            val c = open(n, URL("$BASE/__down?bytes=0"))
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { it.readBytes() }
                if (i > 0) times += ((System.nanoTime() - start) / 1_000_000).toInt() // first one includes TLS setup
            } finally { c.disconnect() }
        }
        return SpeedResult.median(times)
    }

    /** Several parallel streams for [seconds] or until [cap] bytes; fills [meter]. */
    private suspend fun download(n: Network, seconds: Int, cap: Long, meter: ThroughputMeter, tick: () -> Unit) = coroutineScope {
        val total = AtomicLong()
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        val stop = { System.currentTimeMillis() > deadline || total.get() >= cap }
        meter.sample(System.currentTimeMillis(), 0)
        val workers = List(STREAMS) {
            launch(Dispatchers.IO) {
                val buf = ByteArray(64 * 1024)
                while (coroutineContext.isActive && !stop()) {
                    val c = open(n, URL("$BASE/__down?bytes=$CHUNK"))
                    try {
                        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                        c.inputStream.use { s ->
                            while (!stop()) { val k = s.read(buf); if (k < 0) break; total.addAndGet(k.toLong()) }
                        }
                    } finally { c.disconnect() }
                }
            }
        }
        while (workers.any { it.isActive }) { delay(200); meter.sample(System.currentTimeMillis(), total.get()); tick() }
        meter.sample(System.currentTimeMillis(), total.get())
    }

    private suspend fun upload(n: Network, seconds: Int, cap: Long, meter: ThroughputMeter, tick: () -> Unit) = coroutineScope {
        val total = AtomicLong()
        val deadline = System.currentTimeMillis() + seconds * 1_000L
        val stop = { System.currentTimeMillis() > deadline || total.get() >= cap }
        val body = Random.nextBytes(64 * 1024) // random, so nothing on the path can compress it
        meter.sample(System.currentTimeMillis(), 0)
        val workers = List(UP_STREAMS) {
            launch(Dispatchers.IO) {
                while (coroutineContext.isActive && !stop()) {
                    val c = open(n, URL("$BASE/__up"))
                    try {
                        c.doOutput = true
                        c.requestMethod = "POST"
                        c.setFixedLengthStreamingMode(UP_CHUNK)
                        c.outputStream.use { o ->
                            var left = UP_CHUNK
                            while (left > 0) {
                                if (stop()) throw IOException("time up") // abandons this request
                                val k = minOf(left, body.size.toLong()).toInt()
                                o.write(body, 0, k); left -= k; total.addAndGet(k.toLong())
                            }
                        }
                        c.responseCode
                    } catch (e: IOException) {
                        if (!stop()) throw e
                    } finally { c.disconnect() }
                }
            }
        }
        while (workers.any { it.isActive }) { delay(200); meter.sample(System.currentTimeMillis(), total.get()); tick() }
        meter.sample(System.currentTimeMillis(), total.get())
    }

    private fun open(n: Network, url: URL) = (n.openConnection(url) as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
    }

    companion object {
        private const val BASE = "https://speed.cloudflare.com"
        private const val STREAMS = 4
        private const val UP_STREAMS = 2
        private const val CHUNK = 25_000_000L
        private const val UP_CHUNK = 8L * 1024 * 1024
        private const val DOWN_SECONDS = 7
        private const val UP_SECONDS = 6
        const val CELL_DOWN_CAP = 40L * 1_000_000
        const val CELL_UP_CAP = 15L * 1_000_000
    }
}
