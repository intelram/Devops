package dev.netpilot.app

import android.net.Network
import dev.netpilot.core.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.SSLException

/**
 * Active probe pinned to one Network: DNS -> TCP -> TLS/HTTPS -> captive-portal HTTP.
 * Every step goes through [Network] APIs, so it measures that path even when it is
 * not the default and the process is bound elsewhere.
 *
 * Cost: about 2–3 KB per probe. At the default 15 s interval on a held cellular link
 * that is roughly 15 MB/day. Keep the interval configurable.
 */
class Prober(
    private val httpsUrl: URL = URL("https://www.google.com/generate_204"),
    // Cleartext on purpose: portals intercept HTTP, not HTTPS. Allowed only for this host in network_security_config.xml.
    private val captiveUrl: URL = URL("http://connectivitycheck.gstatic.com/generate_204"),
    private val timeoutMs: Int = 4_000,
) {
    suspend fun probe(network: Network): ProbeResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        var t = System.nanoTime()
        val addrs = runCatching { network.getAllByName(httpsUrl.host) }.getOrNull()
        val dnsMs = ms(t)
        if (addrs.isNullOrEmpty()) {
            return@withContext ProbeResult(false, dnsMs, false, null, null, null, null, null, now)
        }

        t = System.nanoTime()
        val tcpOk = runCatching {
            network.socketFactory.createSocket().use { it.connect(InetSocketAddress(addrs[0], 443), timeoutMs) }
        }.isSuccess
        val tcpMs = ms(t)
        if (!tcpOk) return@withContext ProbeResult(true, dnsMs, false, tcpMs, null, null, null, null, now)

        t = System.nanoTime()
        var tlsOk: Boolean? = null
        val httpsStatus = try {
            get(network, httpsUrl).also { tlsOk = true }
        } catch (e: SSLException) {
            tlsOk = false; null
        } catch (e: Exception) {
            tlsOk = true; null // handshake passed or never reached; treated as an HTTP-level failure
        }
        val httpsMs = ms(t)

        val captive = runCatching { get(network, captiveUrl) }.getOrNull()
        ProbeResult(true, dnsMs, true, tcpMs, httpsStatus, httpsMs, tlsOk, captive, now)
    }

    private fun get(network: Network, url: URL): Int {
        val c = network.openConnection(url) as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false // a redirect is the captive-portal signal
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun ms(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000
}
