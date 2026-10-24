package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals

class UsageMeterTest {
    @Test fun countsDeltasWithinADay() {
        var s = UsageMeter.update(null, "d1", 1, 1_000)
        assertEquals(0, s.today)
        s = UsageMeter.update(s, "d1", 1, 1_500)
        s = UsageMeter.update(s, "d1", 1, 4_000)
        assertEquals(3_000, s.today)
    }

    @Test fun newDayStartsAtZero() {
        var s = UsageMeter.update(null, "d1", 1, 1_000)
        s = UsageMeter.update(s, "d1", 1, 9_000)
        s = UsageMeter.update(s, "d2", 1, 9_500)
        assertEquals(0, s.today)
        s = UsageMeter.update(s, "d2", 1, 10_000)
        assertEquals(500, s.today)
    }

    @Test fun rebootAddsEverythingSinceBoot() {
        var s = UsageMeter.update(null, "d1", 1, 1_000)
        s = UsageMeter.update(s, "d1", 1, 3_000)         // 2 000 today
        s = UsageMeter.update(s, "d1", 2, 700)           // rebooted, 700 since boot
        assertEquals(2_700, s.today)
        s = UsageMeter.update(s, "d1", 2, 1_000)
        assertEquals(3_000, s.today)
    }

    @Test fun unsupportedCountersAreIgnored() {
        val s = UsageMeter.update(null, "d1", 1, -1)
        assertEquals(0, s.today)
    }
}
