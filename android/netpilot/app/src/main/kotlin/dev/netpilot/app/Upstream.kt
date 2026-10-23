package dev.netpilot.app

import android.net.ConnectivityManager
import dev.netpilot.core.Dns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * Forwards an allowed DNS query upstream.
 *
 * 1. DNS-over-HTTPS (RFC 8484, POST application/dns-message) to the chosen provider, with
 *    normal certificate validation. The message id is sent as 0 so HTTP caches can share
 *    answers, and restored afterwards.
 * 2. Optionally, plain DNS to the current network's own DNS servers, so captive-portal
 *    sign-in pages and networks that block DoH still work. The UI shows when this happens.
 *
 * NetPilot excludes itself from its own VPN, so these sockets use the real network directly.
 */
class Upstream(private val cm: ConnectivityManager, private val prefs: Prefs) {
    enum class Via { ENCRYPTED, PLAIN }
    class Answer(val response: ByteArray, val via: Via)

    @Volatile var lastEncryptedOk = true
        private set

    fun resolve(query: ByteArray, id: Int): Answer {
        try {
            val r = doh(prefs.dnsUrl, query)
            lastEncryptedOk = true
            return Answer(Dns.withId(r, id), Via.ENCRYPTED)
        } catch (e: IOException) {
            lastEncryptedOk = false
            if (!prefs.allowPlainFallback) throw e
        }
        return Answer(Dns.withId(plain(query), id), Via.PLAIN)
    }

    private fun doh(url: String, query: ByteArray): ByteArray {
        val body = Dns.withId(query, 0)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4_000
            c.readTimeout = 4_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw IOException("DoH HTTP ${c.responseCode}")
            val type = c.contentType ?: ""
            if (!type.startsWith("application/dns-message")) throw IOException("DoH bad content type")
            val out = ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 65_535) throw IOException("DoH response too large")
                }
            }
            val r = out.toByteArray()
            if (r.size < 12) throw IOException("DoH response too short")
            return r
        } finally {
            c.disconnect() // returns the connection to the keep-alive pool when fully read
        }
    }

    private fun plain(query: ByteArray): ByteArray {
        val net = cm.activeNetwork ?: throw IOException("no network")
        val servers = cm.getLinkProperties(net)?.dnsServers.orEmpty()
        if (servers.isEmpty()) throw IOException("network has no DNS servers")
        var last: IOException? = null
        for (server in servers.take(2)) {
            try {
                DatagramSocket().use { s ->
                    net.bindSocket(s)
                    s.soTimeout = 2_500
                    s.send(DatagramPacket(query, query.size, InetSocketAddress(server, 53)))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (p.length < 12 || Dns.u16(buf, 0) != Dns.u16(query, 0)) throw IOException("bad reply")
                    return buf.copyOf(p.length)
                }
            } catch (e: IOException) { last = e }
        }
        throw last ?: IOException("plain DNS failed")
    }
}
