package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeedTest {
    @Test fun ignoresWarmUp() {
        val m = ThroughputMeter(warmupMs = 1_000)
        m.sample(0, 0)
        m.sample(1_000, 125_000)        // slow start: 1 Mbit/s, ignored
        m.sample(3_000, 125_000 + 2_500_000) // then 10 Mbit/s for 2 s
        assertEquals(10.0, m.mbps()!!, 0.001)
    }

    @Test fun nothingYet() {
        val m = ThroughputMeter()
        assertNull(m.mbps())
        m.sample(0, 0)
        assertNull(m.mbps())
    }

    @Test fun resultRoundTrips() {
        val r = SpeedResult(1_700_000_000_000, LinkSpeed(87.5, 12.25, 18), null, 120.0)
        assertEquals(r, SpeedResult.decode(r.encode()))
        val partial = SpeedResult(5, LinkSpeed(null, null, null), LinkSpeed(30.0, null, 45), null)
        assertEquals(partial, SpeedResult.decode(partial.encode()))
        assertNull(SpeedResult.decode("garbage"))
    }

    @Test fun medianPing() {
        assertEquals(20, SpeedResult.median(listOf(90, 20, 18, 21, 19)))
        assertNull(SpeedResult.median(emptyList()))
    }
}
