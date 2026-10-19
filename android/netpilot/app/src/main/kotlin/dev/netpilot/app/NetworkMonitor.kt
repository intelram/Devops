package dev.netpilot.app

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dev.netpilot.core.LinkSnapshot
import dev.netpilot.core.ProbeResult
import dev.netpilot.core.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the OS has told us about one network, from callbacks only (never polled). */
data class OsLink(
    val network: Network,
    val caps: NetworkCapabilities? = null,
    val lp: LinkProperties? = null,
)

/**
 * Tracks every internet-capable network, not just the default one.
 *
 * With [keepCellularUp] it files a cellular NetworkRequest. That makes Android bring
 * cellular up and keep it up while Wi-Fi is the default. Without it, Android tears
 * cellular down shortly after Wi-Fi validates, and failover would have to wait for
 * the modem to re-attach (often 1–5 s). This costs battery and a small amount of data.
 */
class NetworkMonitor(private val cm: ConnectivityManager) {

    private val _links = MutableStateFlow<Map<Network, OsLink>>(emptyMap())
    val links: StateFlow<Map<Network, OsLink>> = _links

    private val _systemDefault = MutableStateFlow<Network?>(null)
    /** The device-wide default network (what other apps use). Informational only. */
    val systemDefault: StateFlow<Network?> = _systemDefault

    private val allCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            _links.update { it + (network to (it[network] ?: OsLink(network))) }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            _links.update { it + (network to (it[network] ?: OsLink(network)).copy(caps = caps)) }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            _links.update { it + (network to (it[network] ?: OsLink(network)).copy(lp = lp)) }

        override fun onLost(network: Network) = _links.update { it - network }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _systemDefault.value = network }
        override fun onLost(network: Network) { if (_systemDefault.value == network) _systemDefault.value = null }
    }

    // Updates arrive through allCallback; this callback exists only to hold the request open.
    private val cellularHold = object : ConnectivityManager.NetworkCallback() {}
    private var holdingCellular = false
    private var started = false

    fun start(keepCellularUp: Boolean) {
        if (started) return
        started = true
        val all = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // also observe VPNs, for diagnosis
            .build()
        cm.registerNetworkCallback(all, allCallback)
        cm.registerDefaultNetworkCallback(defaultCallback)
        setKeepCellularUp(keepCellularUp)
    }

    /** No-op while stopped; start() applies the caller's choice. */
    fun setKeepCellularUp(enabled: Boolean) {
        if (!started || enabled == holdingCellular) return
        if (enabled) {
            val cell = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(cell, cellularHold) // needs CHANGE_NETWORK_STATE
        } else {
            safeUnregister(cellularHold)
        }
        holdingCellular = enabled
    }

    fun stop() {
        if (!started) return
        started = false
        safeUnregister(allCallback)
        safeUnregister(defaultCallback)
        if (holdingCellular) safeUnregister(cellularHold)
        holdingCellular = false
        _links.value = emptyMap()
    }

    private fun safeUnregister(cb: ConnectivityManager.NetworkCallback) {
        try { cm.unregisterNetworkCallback(cb) } catch (e: IllegalArgumentException) { Log.w(TAG, "callback not registered", e) }
    }

    companion object { private const val TAG = "NetworkMonitor" }
}

fun NetworkCapabilities.transport(): Transport = when {
    hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
    hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
    else -> Transport.OTHER
}

fun OsLink.toSnapshot(probe: ProbeResult?): LinkSnapshot {
    val c = caps
    return LinkSnapshot(
        id = network.toString(),
        transport = c?.transport() ?: Transport.OTHER,
        validated = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captivePortal = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        metered = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        signalDbm = c?.signalStrength?.takeIf { it != Int.MIN_VALUE }, // Int.MIN_VALUE = unspecified
        estDownKbps = c?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
        // Heuristic: strict-mode Private DNS (server name set) on a network Android could not validate.
        // Strict mode fails closed, so a broken resolver is the most common cause of that combination.
        privateDnsBroken = lp?.privateDnsServerName != null &&
            c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true,
        probe = probe,
    )
}
