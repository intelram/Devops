package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FailoverPolicyTest {
    private var now = 0L
    private val cfg = PolicyConfig(failThreshold = 2, recoverThreshold = 3, switchMargin = 15, minDwellMs = 30_000)
    private fun policy() = FailoverPolicy(cfg, LinkScorer(), clock = { now })

    private fun probe(ok: Boolean, ms: Long = 50) = ProbeResult(
        dnsOk = ok, dnsMs = 5, tcpOk = ok, tcpMs = 10, httpsStatus = if (ok) 204 else null,
        httpsMs = if (ok) ms else null, tlsOk = ok, captiveStatus = 204, timestampMs = now,
    )
    private fun wifi(ok: Boolean = true, ms: Long = 50) =
        LinkSnapshot("wifi", Transport.WIFI, validated = ok, captivePortal = false, metered = false, probe = probe(ok, ms))
    private fun cell(ok: Boolean = true, ms: Long = 50) =
        LinkSnapshot("cell", Transport.CELLULAR, validated = ok, captivePortal = false, metered = true, probe = probe(ok, ms))

    @Test fun initialSelectionPrefersWifi() {
        val d = policy().evaluate(Mode.PREFER_WIFI, listOf(cell(), wifi()))
        assertIs<Decision.Switch>(d); assertEquals("wifi", d.toId); assertEquals(false, d.failover)
    }

    @Test fun failsOverOnlyAfterThreshold() {
        val p = policy()
        p.evaluate(Mode.PREFER_WIFI, listOf(wifi(), cell()))
        now += 5_000
        assertIs<Decision.Stay>(p.evaluate(Mode.PREFER_WIFI, listOf(wifi(ok = false), cell())))
        now += 5_000
        val d = p.evaluate(Mode.PREFER_WIFI, listOf(wifi(ok = false), cell()))
        assertIs<Decision.Switch>(d); assertEquals("cell", d.toId); assertTrue(d.failover)
    }

    @Test fun failsOverImmediatelyWhenNetworkLost() {
        val p = policy()
        p.evaluate(Mode.PREFER_WIFI, listOf(wifi(), cell()))
        val d = p.evaluate(Mode.PREFER_WIFI, listOf(cell()))
        assertIs<Decision.Switch>(d); assertEquals("cell", d.toId)
    }

    @Test fun failBackWaitsForRecoveryStreakAndDwell() {
        val p = policy()
        p.evaluate(Mode.PREFER_WIFI, listOf(cell()))                      // only cell present
        now += 1_000
        repeat(3) { assertIs<Decision.Stay>(p.evaluate(Mode.PREFER_WIFI, listOf(wifi(), cell()))); now += 1_000 }
        now += 30_000                                                     // dwell elapsed, wifi stable ×4
        val d = p.evaluate(Mode.PREFER_WIFI, listOf(wifi(), cell()))
        assertIs<Decision.Switch>(d); assertEquals("wifi", d.toId); assertEquals(false, d.failover)
    }

    @Test fun flappingWifiDoesNotCauseFailBack() {
        val p = policy()
        p.evaluate(Mode.PREFER_WIFI, listOf(cell()))
        now += 60_000
        for (i in 0 until 10) {
            val d = p.evaluate(Mode.PREFER_WIFI, listOf(wifi(ok = i % 2 == 0), cell()))
            assertIs<Decision.Stay>(d, "iteration $i")
            now += 5_000
        }
    }

    @Test fun onlyModeNeverSelectsOtherTransport() {
        val d = policy().evaluate(Mode.WIFI_ONLY, listOf(wifi(ok = false), cell()))
        assertIs<Decision.NoUsableLink>(d)
    }

    @Test fun autoSwitchesOnlyWithMargin() {
        val p = policy()
        p.evaluate(Mode.AUTO, listOf(wifi(ms = 300), cell(ms = 280)))
        now += 60_000
        repeat(3) { p.evaluate(Mode.AUTO, listOf(wifi(ms = 300), cell(ms = 280))) }
        assertIs<Decision.Stay>(p.evaluate(Mode.AUTO, listOf(wifi(ms = 300), cell(ms = 280))))
        val d = p.evaluate(Mode.AUTO, listOf(wifi(ms = 900), cell(ms = 40)))
        assertIs<Decision.Switch>(d); assertEquals("cell", d.toId)
    }

    @Test fun captivePortalIsUnusable() {
        val portal = wifi().copy(captivePortal = true)
        val d = policy().evaluate(Mode.PREFER_WIFI, listOf(portal, cell()))
        assertIs<Decision.Switch>(d); assertEquals("cell", d.toId)
    }

    @Test fun vpnIsNeverSelected() {
        val vpn = LinkSnapshot("vpn", Transport.VPN, true, false, false, probe = probe(true))
        assertIs<Decision.NoUsableLink>(policy().evaluate(Mode.AUTO, listOf(vpn)))
    }
}
