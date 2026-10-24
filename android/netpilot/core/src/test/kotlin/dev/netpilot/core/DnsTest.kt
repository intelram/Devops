package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DnsTest {
    /** Builds a query like Android's resolver sends: RD set, one question, optional EDNS OPT record. */
    private fun query(name: String, type: Int, id: Int = 0x1234, edns: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(if (edns) 1 else 0)
        for (label in name.split('.')) { out += label.length.toByte(); label.forEach { out += it.code.toByte() } }
        out += 0; u16(type); u16(1)
        if (edns) { out += 0; u16(41); u16(4096); u16(0); u16(0); u16(0) }
        return out.toByteArray()
    }

    @Test fun parsesQueryLowercased() {
        val q = assertNotNull(Dns.parseQuery(query("Ads.Example.COM", Dns.TYPE_A, edns = true)))
        assertEquals("ads.example.com", q.name)
        assertEquals(Dns.TYPE_A, q.type)
        assertEquals(0x1234, q.id)
    }

    @Test fun rejectsResponsesAndJunk() {
        val r = query("a.com", 1).also { it[2] = 0x81.toByte() }
        assertNull(Dns.parseQuery(r))
        assertNull(Dns.parseQuery(ByteArray(5)))
        val truncated = query("example.com", 1).copyOf(20)
        assertNull(Dns.parseQuery(truncated))
    }

    @Test fun blockedAIsZeroAddressAndParsable() {
        val q = Dns.parseQuery(query("ads.example.com", Dns.TYPE_A))!!
        val r = Dns.blockedResponse(q)
        assertEquals(0x1234, Dns.u16(r, 0))
        assertEquals(0x8180, Dns.u16(r, 2))        // QR RD RA, NOERROR
        assertEquals(1, Dns.u16(r, 6))             // one answer
        assertContentEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
        assertEquals(10, Dns.minAnswerTtl(r))
    }

    @Test fun blockedAaaaIs16ZeroBytes() {
        val r = Dns.blockedResponse(Dns.parseQuery(query("t.example.org", Dns.TYPE_AAAA))!!)
        assertEquals(16, Dns.u16(r, r.size - 18))
        assertContentEquals(ByteArray(16), r.copyOfRange(r.size - 16, r.size))
    }

    @Test fun blockedOtherTypeIsNxdomain() {
        val r = Dns.blockedResponse(Dns.parseQuery(query("t.example.org", 65))!!)
        assertEquals(Dns.RCODE_NXDOMAIN, Dns.u16(r, 2) and 0xF)
        assertEquals(0, Dns.u16(r, 6))
    }

    @Test fun servfailCopiesQuestion() {
        val raw = query("x.example.net", 1)
        val r = Dns.errorResponse(Dns.parseQuery(raw)!!)
        assertEquals(Dns.RCODE_SERVFAIL, Dns.u16(r, 2) and 0xF)
        assertContentEquals(raw.copyOfRange(12, raw.size), r.copyOfRange(12, r.size))
    }

    @Test fun withIdRewritesOnlyId() {
        val r = Dns.blockedResponse(Dns.parseQuery(query("a.example.com", 1, id = 7))!!)
        val r2 = Dns.withId(r, 0xBEEF)
        assertEquals(0xBEEF, Dns.u16(r2, 0))
        assertContentEquals(r.copyOfRange(2, r.size), r2.copyOfRange(2, r2.size))
    }

    @Test fun cacheHonoursTtlAndId() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        val r = Dns.blockedResponse(Dns.parseQuery(query("a.example.com", 1))!!) // TTL 10, clamped up to the 30 s minimum
        cache.put("a.example.com", 1, r)
        assertEquals(0x55, Dns.u16(cache.get("a.example.com", 1, 0x55)!!, 0))
        now += 29_000
        assertNotNull(cache.get("a.example.com", 1, 1))
        now += 2_000
        assertNull(cache.get("a.example.com", 1, 1))
    }

    @Test fun cacheSkipsErrors() {
        val cache = DnsCache()
        cache.put("x.com", 1, Dns.errorResponse(Dns.parseQuery(query("x.com", 1))!!))
        assertNull(cache.get("x.com", 1, 1))
    }

    @Test fun truncatedKeepsQuestionAndSetsTc() {
        val raw = query("big.example.com", 16)
        val full = Dns.blockedResponse(Dns.parseQuery(raw)!!) // stands in for any answer
        val t = Dns.truncated(full)
        assertEquals(0x0200, Dns.u16(t, 2) and 0x0200)
        assertEquals(1, Dns.u16(t, 4)); assertEquals(0, Dns.u16(t, 6))
        assertContentEquals(raw.copyOfRange(12, raw.size), t.copyOfRange(12, t.size))
    }

    /** metrics.shop.example CNAME shop.tracker.example (compressed), then an A record for it. */
    private fun cloakedAnswer(): ByteArray {
        val q = query("metrics.shop.example", Dns.TYPE_A)
        val out = ArrayList<Byte>(q.toList())
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        out[2] = 0x81.toByte(); out[3] = 0x80.toByte(); out[7] = 2 // response, 2 answers
        // Answer 1: name -> pointer to question (offset 12), CNAME, rdata "shop.tracker" + pointer to "example".
        val rdata = byteArrayOf(4, 's'.code.toByte(), 'h'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(),
            7) + "TRACKER".toByteArray() + byteArrayOf(0xC0.toByte(), (12 + 1 + 7 + 1 + 4).toByte())
        u16(0xC00C); u16(Dns.TYPE_CNAME); u16(1); u16(0); u16(300); u16(rdata.size); rdata.forEach { out += it }
        // Answer 2: pointer to the CNAME target, A 192.0.2.1.
        val target = q.size + 12
        u16(0xC000 or target); u16(Dns.TYPE_A); u16(1); u16(0); u16(300); u16(4)
        out += 192.toByte(); out += 0; out += 2; out += 1
        return out.toByteArray()
    }

    @Test fun cnameTargetsFollowCompression() {
        assertEquals(listOf("shop.tracker.example"), Dns.cnameTargets(cloakedAnswer()))
    }

    @Test fun cnameTargetsSafeOnJunk() {
        assertEquals(emptyList(), Dns.cnameTargets(ByteArray(3)))
        val r = cloakedAnswer()
        assertEquals(emptyList(), Dns.cnameTargets(r.copyOf(r.size - 30))) // cut inside the CNAME
        // A pointer that points at itself must not hang.
        val self = query("a.example", 1).toMutableList()
        self[7] = 1
        val at = self.size
        self += listOf(0xC0.toByte(), at.toByte(), 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0.toByte(), (at + 12).toByte())
        assertEquals(emptyList(), Dns.cnameTargets(self.toByteArray()))
    }

    @Test fun answersOnlyItsOwnQuestion() {
        val q = query("www.example.com", Dns.TYPE_A)
        val good = Dns.blockedResponse(Dns.parseQuery(q)!!)
        kotlin.test.assertTrue(Dns.answers(q, good))
        // Case may differ (0x20 randomisation), the name may not.
        kotlin.test.assertTrue(Dns.answers(query("WWW.Example.com", Dns.TYPE_A), good))
        kotlin.test.assertFalse(Dns.answers(q, Dns.blockedResponse(Dns.parseQuery(query("www.evil.com", Dns.TYPE_A))!!)))
        kotlin.test.assertFalse(Dns.answers(q, Dns.blockedResponse(Dns.parseQuery(query("www.example.com", Dns.TYPE_AAAA))!!)))
        kotlin.test.assertFalse(Dns.answers(q, q))                 // a query is not an answer
        kotlin.test.assertFalse(Dns.answers(q, good.copyOf(14)))   // truncated
    }
}
