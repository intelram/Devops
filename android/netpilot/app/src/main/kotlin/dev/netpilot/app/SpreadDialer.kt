package dev.netpilot.app

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.netpilot.core.Dialer
import dev.netpilot.core.SpreadPolicy
import dev.netpilot.core.Transport
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * Opens each forwarded connection on Wi-Fi or mobile data, chosen by [SpreadPolicy]. NetPilot
 * is excluded from its own VPN, and every socket is also bound to an explicit network, so
 * forwarded traffic can never loop back into the tunnel.
 */
class SpreadDialer(
    private val cm: ConnectivityManager,
    /** True for "maximum speed": share out every connection, even several to the same server. */
    private val splitSites: () -> Boolean = { false },
    private val speedFor: (Transport) -> Double?,
) : Dialer {

    class Link(val id: String, val network: Network, val transport: Transport, val weight: Int, val ipv6: Boolean)

    private val policy = SpreadPolicy()
    @Volatile var links: List<Link> = emptyList()
        private set

    /**
     * Re-reads the usable networks: internet, not a VPN, validated when possible. Weights come from the last
     * speed test when there is one, otherwise from Android's own bandwidth estimate.
     * Returns true if the set of networks changed.
     */
    fun refresh(): Boolean {
        val validated = ArrayList<Link>()
        val other = ArrayList<Link>()
        @Suppress("DEPRECATION") // getAllNetworks: still the only way to list every network without callbacks
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) continue
            val t = c.transport()
            if (t != Transport.WIFI && t != Transport.CELLULAR && t != Transport.ETHERNET) continue
            val estimate = c.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { it / 1_000.0 }
            val lp = cm.getLinkProperties(n)
            val v6 = lp?.routes?.any { it.isDefaultRoute && it.destination.address is Inet6Address } == true
            val link = Link(idFor(t), n, t, SpreadPolicy.weightFor(speedFor(t) ?: estimate), v6)
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated += link else other += link
        }
        // Android's internet check can fail on networks that work (e.g. where its check servers are
        // blocked). With no validated network, use what's connected rather than cut everything off.
        val next = validated.ifEmpty { other }
        val changed = next.map { it.network } != links.map { it.network }
        links = next
        return changed
    }

    private fun candidates(ipv6: Boolean) = links.filter { !ipv6 || it.ipv6 }.map { SpreadPolicy.Candidate(it.id, it.weight) }

    override fun connectTcp(dst: InetSocketAddress, timeoutMs: Int): Dialer.Tcp {
        val v6 = dst.address is Inet6Address
        val key = dst.address.hostAddress ?: throw IOException("no address")
        var failed: String? = null
        var last: IOException? = null
        repeat(2) {
            val id = policy.pick(key, candidates(v6), exclude = failed, sticky = !splitSites()) ?: throw last ?: IOException("no network")
            val link = links.firstOrNull { it.id == id } ?: throw IOException("network gone")
            try {
                val s = link.network.socketFactory.createSocket()
                try {
                    s.tcpNoDelay = true
                    s.connect(dst, timeoutMs)
                } catch (e: IOException) { s.close(); throw e }
                return Dialer.Tcp(s, id)
            } catch (e: IOException) {
                // Try the other network once: a connection that fails on one often works on the other.
                failed = id; last = e
            }
        }
        throw last ?: IOException("connect failed")
    }

    override fun openUdp(dst: InetSocketAddress): Dialer.Udp {
        val v6 = dst.address is Inet6Address
        val id = policy.pick(dst.address.hostAddress ?: "", candidates(v6), sticky = !splitSites()) ?: throw IOException("no network")
        val link = links.firstOrNull { it.id == id } ?: throw IOException("network gone")
        val s = DatagramSocket()
        try {
            link.network.bindSocket(s)
            s.connect(dst)
        } catch (e: Exception) { s.close(); throw IOException(e) }
        return Dialer.Udp(s, id)
    }

    companion object {
        const val WIFI = "wifi"
        const val CELL = "cell"
        const val WIRED = "wired"
        fun idFor(t: Transport) = when (t) { Transport.CELLULAR -> CELL; Transport.ETHERNET -> WIRED; else -> WIFI }
    }
}
