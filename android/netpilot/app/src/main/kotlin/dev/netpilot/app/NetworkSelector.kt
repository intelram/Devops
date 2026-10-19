package dev.netpilot.app

import android.net.ConnectivityManager
import android.net.Network

/**
 * Steers this app's traffic only. bindProcessToNetwork makes every new socket and
 * DNS lookup in this process use [network]. Sockets that are already open stay on
 * their old network, so callers must reconnect after a switch (for OkHttp, call
 * connectionPool.evictAll()).
 *
 * No public API lets a normal app choose the network for another app's traffic.
 * The only system-wide lever is VpnService (see docs/DESIGN.md §2.4).
 */
class NetworkSelector(private val cm: ConnectivityManager) {

    fun bind(network: Network?): Boolean = cm.bindProcessToNetwork(network)

    val bound: Network? get() = cm.boundNetworkForProcess
}
