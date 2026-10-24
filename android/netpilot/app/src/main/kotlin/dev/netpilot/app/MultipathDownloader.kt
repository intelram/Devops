package dev.netpilot.app

import android.net.Network
import dev.netpilot.core.ByteRange
import dev.netpilot.core.ChunkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app multipath download: real aggregate throughput for ONE kind of traffic
 * (this app downloading a file over HTTP(S)) with no server of our own. Each
 * network fetches different byte ranges of the same file from an ordinary server
 * that supports `Range` requests (206 Partial Content).
 *
 * This is application-layer striping. It is not link bonding: it does nothing for
 * other apps, uploads, streams, or servers without Range support.
 *
 * Integrity: every range carries `If-Range: <ETag or Last-Modified>`, so a server
 * that changed the object answers 200 instead of 206 and we abort instead of
 * stitching two versions together. Pass [expectedSha256] for end-to-end verification.
 */
class MultipathDownloader(
    private val workersPerLink: Int = 2,
    private val chunkBytes: Long = 1L shl 20,
    private val timeoutMs: Int = 10_000,
) {
    data class Result(val bytes: Long, val millis: Long, val bytesPerLink: Map<String, Long>, val sha256: String)

    class NotRangeCapable(msg: String) : IOException(msg)

    private data class Meta(val length: Long, val validator: String)

    suspend fun download(
        url: URL,
        networks: Map<String, Network>,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        require(networks.isNotEmpty()) { "no networks" }
        require(url.protocol == "https") { "https only" }
        val meta = probeMeta(networks.values.first(), url)
        val sched = ChunkScheduler(meta.length, chunkBytes)
        val start = System.currentTimeMillis()

        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(meta.length)
            coroutineScope {
                for ((id, net) in networks) repeat(workersPerLink) {
                    launch { worker(id, net, url, meta, sched, raf, onProgress) }
                }
            }
        }
        check(sched.isDone) { "incomplete: ${sched.completed()}/${meta.length} bytes; every link failed" }

        val sha = sha256(dest)
        if (expectedSha256 != null && !sha.equals(expectedSha256, ignoreCase = true)) {
            dest.delete()
            throw IOException("SHA-256 mismatch: got $sha")
        }
        Result(meta.length, System.currentTimeMillis() - start, sched.bytesPerLink(), sha)
    }

    private suspend fun worker(
        id: String, net: Network, url: URL, meta: Meta, sched: ChunkScheduler,
        raf: RandomAccessFile, onProgress: (Long, Long) -> Unit,
    ) {
        var failures = 0
        while (true) {
            val r = sched.next(id)
            if (r == null) {
                // Ranges another link failed get requeued; keep polling while work is outstanding.
                if (!sched.hasWorkLeft) return
                delay(200); continue
            }
            try {
                currentCoroutineContext().ensureActive()
                val body = fetchRange(net, url, r, meta.validator)
                synchronized(raf) { raf.seek(r.start); raf.write(body) }
                sched.complete(r, id)
                failures = 0
                onProgress(sched.completed(), meta.length)
            } catch (e: NotRangeCapable) {
                sched.fail(r, id); throw e // object changed mid-download: abort everything
            } catch (e: IOException) {
                sched.fail(r, id)
                if (++failures >= 3) return // give this link up; others drain the queue
                delay(500L * failures)
            }
        }
    }

    private fun probeMeta(net: Network, url: URL): Meta {
        val c = open(net, url).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            if (c.responseCode != 206) throw NotRangeCapable("server answered ${c.responseCode} to a Range request")
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw NotRangeCapable("no total length in Content-Range")
            val validator = c.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") }
                ?: c.getHeaderField("Last-Modified")
                ?: throw NotRangeCapable("no strong ETag or Last-Modified; cannot guarantee consistency")
            return Meta(total, validator)
        } finally { c.disconnect() }
    }

    private fun fetchRange(net: Network, url: URL, r: ByteRange, validator: String): ByteArray {
        val c = open(net, url).apply {
            setRequestProperty("Range", r.header())
            setRequestProperty("If-Range", validator)
        }
        try {
            when (c.responseCode) {
                206 -> Unit
                200 -> throw NotRangeCapable("object changed on server (If-Range failed)")
                else -> throw IOException("HTTP ${c.responseCode}")
            }
            val expect = "bytes ${r.start}-${r.endInclusive}/"
            if (c.getHeaderField("Content-Range")?.startsWith(expect) != true) throw IOException("wrong Content-Range")
            val body = c.inputStream.use { it.readBytes() }
            if (body.size.toLong() != r.length) throw IOException("short body ${body.size}/${r.length}")
            return body
        } finally { c.disconnect() }
    }

    private fun open(net: Network, url: URL) = (net.openConnection(url) as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity") // byte offsets must refer to the raw object
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
