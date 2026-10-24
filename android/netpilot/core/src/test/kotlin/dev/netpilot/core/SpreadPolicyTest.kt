package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpreadPolicyTest {
    private val two = listOf(SpreadPolicy.Candidate("wifi", 2), SpreadPolicy.Candidate("cell", 1))

    @Test fun sharesNewDestinationsByWeight() {
        val p = SpreadPolicy()
        val picks = (1..300).map { p.pick("10.0.0.$it", two) }
        assertEquals(200, picks.count { it == "wifi" })
        assertEquals(100, picks.count { it == "cell" })
    }

    @Test fun destinationStaysOnItsNetwork() {
        val p = SpreadPolicy()
        val first = p.pick("bank.example", two)
        repeat(20) { assertEquals(first, p.pick("bank.example", two)) }
    }

    @Test fun stickinessExpiresAndFollowsAvailability() {
        var now = 0L
        val p = SpreadPolicy(stickyMs = 1_000, clock = { now })
        val first = p.pick("a", two)!!
        val other = if (first == "wifi") "cell" else "wifi"
        // The network went away: move, and stay moved.
        assertEquals(other, p.pick("a", two.filter { it.id != first }))
        assertEquals(other, p.pick("a", two))
        now += 2_000
        p.pick("a", two) // expired: a fresh choice is allowed (any)
    }

    @Test fun failedNetworkIsExcluded() {
        val p = SpreadPolicy()
        val first = p.pick("x", two)!!
        val retry = p.pick("x", two, exclude = first)
        assertEquals(if (first == "wifi") "cell" else "wifi", retry)
        assertNull(p.pick("y", listOf(SpreadPolicy.Candidate("wifi", 2)), exclude = "wifi"))
    }

    @Test fun weights() {
        assertEquals(10, SpreadPolicy.weightFor(null))
        assertEquals(1, SpreadPolicy.weightFor(0.2))
        assertEquals(87, SpreadPolicy.weightFor(87.4))
    }

    @Test fun maximumSpeedSplitsOneServer() {
        val p = SpreadPolicy()
        val picks = (1..30).map { p.pick("speedtest.example", two, sticky = false) }
        assertEquals(20, picks.count { it == "wifi" })
        assertEquals(10, picks.count { it == "cell" })
    }
}
