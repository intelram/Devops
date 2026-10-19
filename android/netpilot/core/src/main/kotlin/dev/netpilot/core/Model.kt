package dev.netpilot.core

enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

/**
 * User-selected steering mode. Applies only to traffic from this app's own
 * process (ConnectivityManager.bindProcessToNetwork). It never changes how
 * other apps route.
 */
enum class Mode { AUTO, PREFER_WIFI, PREFER_CELLULAR, WIFI_ONLY, CELLULAR_ONLY }

/** Result of one active probe pinned to a single Network. Times in ms; null = step not reached. */
data class ProbeResult(
    val dnsOk: Boolean,
    val dnsMs: Long?,
    val tcpOk: Boolean,
    val tcpMs: Long?,
    /** HTTPS probe status. 204 expected. */
    val httpsStatus: Int?,
    val httpsMs: Long?,
    /** False when the TLS handshake failed (MITM, clock skew, captive portal). */
    val tlsOk: Boolean?,
    /** Plain-HTTP captive-portal probe status. Anything but 204 suggests interception. */
    val captiveStatus: Int?,
    val timestampMs: Long,
) {
    val reachable: Boolean get() = dnsOk && tcpOk && tlsOk == true && httpsStatus == 204
}

/** Point-in-time view of one network: OS-reported capabilities plus our latest probe. */
data class LinkSnapshot(
    /** Opaque, stable for the lifetime of the Network object (netId). */
    val id: String,
    val transport: Transport,
    val validated: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    /** NetworkCapabilities.signalStrength (dBm for Wi-Fi/cell), null if unspecified. */
    val signalDbm: Int? = null,
    /** NetworkCapabilities.linkDownstreamBandwidthKbps: a coarse OS estimate, not a measurement. */
    val estDownKbps: Int? = null,
    val privateDnsBroken: Boolean = false,
    val probe: ProbeResult? = null,
)

/** Device-wide state that affects every link. */
data class DeviceState(
    val anyVpnActive: Boolean = false,
    val dataSaverOn: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val airplaneMode: Boolean = false,
)
