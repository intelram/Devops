package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnoserTest {
    private val d = Diagnoser()
    private fun link(probe: ProbeResult?, captive: Boolean = false) =
        LinkSnapshot("w", Transport.WIFI, validated = probe?.reachable ?: false, captivePortal = captive, metered = false, probe = probe)
    private fun p(dns: Boolean = true, tcp: Boolean = true, tls: Boolean? = true, code: Int? = 204, ms: Long = 50, captive: Int? = 204) =
        ProbeResult(dns, 5, tcp, 10, code, ms, tls, captive, 0)

    private fun codes(l: LinkSnapshot) = d.diagnose(DeviceState(), listOf(l)).filter { it.linkId == "w" }.map { it.code }

    @Test fun firstFailingStepIsReported() {
        assertEquals(listOf("DNS_FAIL"), codes(link(p(dns = false, tcp = false, tls = null, code = null))))
        assertEquals(listOf("TCP_FAIL"), codes(link(p(tcp = false, tls = null, code = null))))
        assertEquals(listOf("TLS_FAIL"), codes(link(p(tls = false, code = null))))
        assertEquals(listOf("HTTP_UNEXPECTED"), codes(link(p(code = 403))))
        assertEquals(listOf("HIGH_LATENCY"), codes(link(p(ms = 1500))))
        assertEquals(emptyList(), codes(link(p())))
    }

    @Test fun captivePortalShortCircuits() {
        assertEquals(listOf("CAPTIVE_PORTAL"), codes(link(p(captive = 302))))
        assertEquals(listOf("CAPTIVE_PORTAL"), codes(link(null, captive = true)))
    }

    @Test fun deviceFindingsAndOrdering() {
        val f = d.diagnose(DeviceState(airplaneMode = true, anyVpnActive = true), emptyList())
        assertEquals(Severity.ERROR, f.first().severity)
        assertTrue(f.any { it.code == "VPN_ACTIVE" } && f.any { it.code == "NO_CELLULAR" })
    }
}
