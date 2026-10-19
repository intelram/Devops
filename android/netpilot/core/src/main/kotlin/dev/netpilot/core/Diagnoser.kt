package dev.netpilot.core

enum class Severity { INFO, WARN, ERROR }

data class Finding(
    val severity: Severity,
    val code: String,
    val linkId: String?,
    val message: String,
    val remediation: String,
)

/**
 * Turns snapshots into human-readable findings. Probe order is
 * DNS -> TCP -> TLS -> HTTP, so the first failing step localises the fault.
 */
class Diagnoser(
    private val highRttMs: Long = 600,
) {
    fun diagnose(device: DeviceState, links: List<LinkSnapshot>): List<Finding> {
        val out = mutableListOf<Finding>()
        if (device.airplaneMode) out += Finding(Severity.ERROR, "AIRPLANE_MODE", null,
            "Airplane mode is on.", "Turn off airplane mode or enable Wi-Fi manually.")
        if (device.dataSaverOn) out += Finding(Severity.WARN, "DATA_SAVER", null,
            "Data Saver is on; background use of metered networks is restricted.",
            "Allow unrestricted data for NetPilot in Settings > Apps > NetPilot > Mobile data.")
        if (device.backgroundRestricted) out += Finding(Severity.WARN, "BG_RESTRICTED", null,
            "The app is background-restricted; monitoring stops when it leaves the screen.",
            "Set battery usage to Unrestricted for NetPilot.")
        if (device.anyVpnActive) out += Finding(Severity.INFO, "VPN_ACTIVE", null,
            "A VPN is active. Other apps' traffic follows the VPN, not NetPilot's selection.",
            "Expected. Disconnect the VPN only if you are diagnosing the VPN itself.")

        val physical = links.filter { it.transport != Transport.VPN }
        if (physical.none { it.transport == Transport.WIFI }) out += Finding(Severity.INFO, "NO_WIFI", null,
            "No Wi-Fi network connected.", "Connect to Wi-Fi for a second path.")
        if (physical.none { it.transport == Transport.CELLULAR }) out += Finding(Severity.INFO, "NO_CELLULAR", null,
            "No cellular data network available.",
            "Check that mobile data is on, a SIM/eSIM is active, and 'Keep cellular up' is enabled.")

        for (l in physical) out += diagnoseLink(l)
        return out.sortedByDescending { it.severity }
    }

    private fun diagnoseLink(l: LinkSnapshot): List<Finding> {
        val name = l.transport.name.lowercase()
        val p = l.probe
        val f = mutableListOf<Finding>()
        fun add(sev: Severity, code: String, msg: String, fix: String) = f.add(Finding(sev, code, l.id, msg, fix))

        if (l.captivePortal || (p != null && p.captiveStatus != null && p.captiveStatus != 204)) {
            add(Severity.ERROR, "CAPTIVE_PORTAL", "$name: captive portal detected (sign-in page).",
                "Open the network's sign-in page from the system notification.")
            return f
        }
        if (l.privateDnsBroken) add(Severity.ERROR, "PRIVATE_DNS", "$name: strict Private DNS is set and the network is not validated; the DNS server is likely unreachable.",
            "Fix or disable Private DNS in Settings > Network > Private DNS.")
        if (p == null) {
            if (!l.validated) add(Severity.WARN, "NOT_VALIDATED", "$name: Android has not validated internet access yet.",
                "Wait a few seconds; if it persists the network has no upstream connectivity.")
            return f
        }
        when {
            !p.dnsOk -> add(Severity.ERROR, "DNS_FAIL", "$name: DNS resolution failed.",
                "Router/carrier DNS is down or blocked. Try Private DNS (e.g. dns.google) or another network.")
            !p.tcpOk -> add(Severity.ERROR, "TCP_FAIL", "$name: DNS works but TCP connect to :443 failed.",
                "Upstream outage or firewall. Reboot the router or check carrier status.")
            p.tlsOk == false -> add(Severity.ERROR, "TLS_FAIL", "$name: TLS handshake failed.",
                "Possible interception (corporate proxy, portal) or wrong device clock. Check date/time.")
            p.httpsStatus != 204 -> add(Severity.ERROR, "HTTP_UNEXPECTED", "$name: HTTPS probe returned ${p.httpsStatus}.",
                "A proxy or filter is altering traffic on this network.")
            (p.httpsMs ?: 0) > highRttMs -> add(Severity.WARN, "HIGH_LATENCY", "$name: probe took ${p.httpsMs} ms.",
                "Congested or weak link. Move closer to the AP or check cellular signal.")
        }
        if (l.signalDbm != null && l.signalDbm < -100) add(Severity.WARN, "WEAK_SIGNAL",
            "$name: weak signal (${l.signalDbm} dBm).", "Expect drops; failover will trigger if probes fail.")
        if (p.reachable && !l.validated) add(Severity.INFO, "PROBE_OK_OS_NOT_VALIDATED",
            "$name: our probe succeeds but Android has not marked it validated.",
            "Usually transient. Android's own probe may be blocked on this network.")
        return f
    }
}
