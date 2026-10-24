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
}
