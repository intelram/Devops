package dev.netpilot.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainSetTest {
    @Test fun parsesCommonListFormats() {
        assertEquals("ads.example.com", DomainSet.parseLine("0.0.0.0 ads.example.com # tracker"))
        assertEquals("ads.example.com", DomainSet.parseLine("127.0.0.1\tADS.example.com."))
        assertEquals("track.example.net", DomainSet.parseLine("track.example.net"))
        assertEquals("ads.example.org", DomainSet.parseLine("||ads.example.org^"))
        assertEquals("cdn.example.org", DomainSet.parseLine("*.cdn.example.org"))
        assertNull(DomainSet.parseLine("# comment"))
        assertNull(DomainSet.parseLine("! adblock comment"))
        assertNull(DomainSet.parseLine("0.0.0.0 localhost"))
        assertNull(DomainSet.parseLine("||ads.example.org^\$third-party"))
        assertNull(DomainSet.parseLine("example.com/path"))
        assertNull(DomainSet.parseLine("1.2.3.4"))
        assertNull(DomainSet.parseLine("com"))
    }

    @Test fun matchesSubdomainsButNotTld() {
        val s = DomainSet.of(listOf("ads.example.com", "tracker.net"))
        assertTrue(s.matches("ads.example.com"))
        assertTrue(s.matches("x.y.ads.example.com"))
        assertTrue(s.matches("cdn.tracker.net"))
        assertFalse(s.matches("example.com"))
        assertFalse(s.matches("goodads.example.com"))
        assertFalse(s.matches("net"))
    }

    @Test fun dedupesAndSerialises() {
        val s = DomainSet.of(listOf("a.com", "A.com", "b.com", "bad domain"))
        assertEquals(2, s.size)
        val bytes = ByteArrayOutputStream().also { s.writeTo(it) }.toByteArray()
        val back = DomainSet.readFrom(ByteArrayInputStream(bytes))
        assertEquals(2, back.size)
        assertTrue(back.matches("x.b.com"))
    }

    @Test fun filterOrderAllowBeatsBlock() {
        val f = DomainFilter(
            lists = mapOf("ads" to DomainSet.of(listOf("ads.example.com", "metrics.shop.com"))),
            userBlocked = setOf("annoying.com"),
            userAllowed = setOf("metrics.shop.com"),
        )
        assertEquals(Verdict(true, BlockReason.LIST, "ads"), f.check("ads.example.com"))
        assertEquals(Verdict(true, BlockReason.USER_BLOCKED), f.check("cdn.annoying.com"))
        assertEquals(Verdict(false, userAllowed = true), f.check("a.metrics.shop.com"))
        assertEquals(Verdict(false), f.check("example.com"))
    }

    @Test fun qualityLabels() {
        assertEquals(Quality.NOT_WORKING, qualityOf(false, 10))
        assertEquals(Quality.EXCELLENT, qualityOf(true, 90))
        assertEquals(Quality.FAIR, qualityOf(true, 600))
        assertEquals(Quality.POOR, qualityOf(true, 2000))
    }

    @Test fun aliasToBlockedDomainIsHiddenTracker() {
        val f = DomainFilter(mapOf("ads" to DomainSet.of(listOf("tracker.example"))), emptySet(), setOf("allowed.example"))
        val v = f.check("metrics.shop.example")
        assertFalse(v.blocked)
        val hidden = f.checkAliases(v, listOf("edge.cdn.example", "shop.tracker.example"))
        assertEquals(Verdict(true, BlockReason.HIDDEN_TRACKER, "ads"), hidden)
        assertNull(f.checkAliases(v, listOf("edge.cdn.example")))
        // The user's allow wins over a blocked alias.
        assertNull(f.checkAliases(f.check("allowed.example"), listOf("shop.tracker.example")))
    }

    @Test fun neverBlocksPhoneHealthChecks() {
        val f = DomainFilter(mapOf("bad" to DomainSet.of(listOf("gstatic.com", "google.com"))), emptySet(), emptySet())
        assertFalse(f.check("connectivitycheck.gstatic.com").blocked)
        assertTrue(f.check("ads.gstatic.com").blocked)
        // The user can still block them on purpose.
        val g = DomainFilter(emptyMap(), setOf("connectivitycheck.gstatic.com"), emptySet())
        assertTrue(g.check("connectivitycheck.gstatic.com").blocked)
    }
}
