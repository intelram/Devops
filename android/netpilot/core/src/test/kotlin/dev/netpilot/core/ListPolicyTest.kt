package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListPolicyTest {
    private val hour = 60 * 60 * 1000L

    @Test fun fetchesMissingOrForcedListsAlways() {
        assertTrue(ListPolicy.shouldFetch(force = false, have = false, ageMs = 0, sparing = true))
        assertTrue(ListPolicy.shouldFetch(force = true, have = true, ageMs = hour, sparing = true))
    }

    @Test fun dailyOnWifiWeeklyOnMobile() {
        assertFalse(ListPolicy.shouldFetch(false, true, 23 * hour, sparing = false))
        assertTrue(ListPolicy.shouldFetch(false, true, 25 * hour, sparing = false))
        assertFalse(ListPolicy.shouldFetch(false, true, 6 * ListPolicy.DAY_MS, sparing = true))
        assertTrue(ListPolicy.shouldFetch(false, true, 7 * ListPolicy.DAY_MS, sparing = true))
    }

    @Test fun rejectsTinyAndSharplyShrunkLists() {
        assertNotNull(ListPolicy.rejectReason(newCount = 999, previousCount = 0, minDomains = 1_000))
        assertNull(ListPolicy.rejectReason(newCount = 3, previousCount = 0, minDomains = 1)) // custom list
        assertEquals("list shrank from 100000 to 49999 domains", ListPolicy.rejectReason(49_999, 100_000, 1_000))
        assertNull(ListPolicy.rejectReason(60_000, 100_000, 1_000)) // normal churn
        assertNull(ListPolicy.rejectReason(10, 900, 1))            // small custom lists may change freely
    }
}
