package dev.netpilot.core

/** One DNS question parsed from a client query (RFC 1035 §4.1). */
class DnsQuery(
    val id: Int,
    /** Lower-case, no trailing dot. */
    val name: String,
    val type: Int,
    /** Offset just past the question section, so the question can be copied verbatim. */
    val questionEnd: Int,
    val message: ByteArray,
)

object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    // Short, so "Pause" and "Allow" take effect within seconds despite the OS resolver cache.
    private const val BLOCKED_TTL = 10

    /** Parses a standard query with exactly one question. Returns null for anything else. */
    fun parseQuery(msg: ByteArray): DnsQuery? {
        if (msg.size < 12) return null
        val flags = u16(msg, 2)
        if (flags and 0x8000 != 0) return null          // QR set: a response, not a query
        if ((flags shr 11) and 0xF != 0) return null    // opcode must be QUERY
        if (u16(msg, 4) != 1) return null               // QDCOUNT
        val sb = StringBuilder()
        var p = 12
        while (true) {
            if (p >= msg.size) return null
            val len = msg[p].toInt() and 0xFF
            if (len == 0) { p++; break }
            if (len and 0xC0 != 0) return null          // no compression pointers in a question we parse
            if (len > 63 || p + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..len) sb.append((msg[p + i].toInt() and 0xFF).toChar().lowercaseChar())
            p += 1 + len
            if (sb.length > 253) return null
        }
        if (p + 4 > msg.size) return null
        val type = u16(msg, p)
        return DnsQuery(u16(msg, 0), sb.toString(), type, p + 4, msg)
    }

    /**
     * Answer for a blocked name: A -> 0.0.0.0, AAAA -> ::, any other type -> NXDOMAIN.
     * Unspecified addresses make connections fail instantly instead of timing out.
     */
    fun blockedResponse(q: DnsQuery): ByteArray {
        val question = q.message.copyOfRange(12, q.questionEnd)
        val addrLen = when (q.type) { TYPE_A -> 4; TYPE_AAAA -> 16; else -> 0 }
        val answerLen = if (addrLen > 0) 12 + addrLen else 0
        val out = ByteArray(12 + question.size + answerLen)
        header(out, q, rcode = if (addrLen > 0) 0 else RCODE_NXDOMAIN, answers = if (addrLen > 0) 1 else 0)
        question.copyInto(out, 12)
        if (addrLen > 0) {
            var p = 12 + question.size
            out[p++] = 0xC0.toByte(); out[p++] = 12           // pointer to the question name
            put16(out, p, q.type); p += 2
            put16(out, p, 1); p += 2                           // class IN
            put32(out, p, BLOCKED_TTL); p += 4
            put16(out, p, addrLen)                             // RDATA is all zeros
        }
        return out
    }

    /** SERVFAIL so the client's resolver retries or fails fast instead of waiting for a timeout. */
    fun errorResponse(q: DnsQuery, rcode: Int = RCODE_SERVFAIL): ByteArray {
        val question = q.message.copyOfRange(12, q.questionEnd)
        val out = ByteArray(12 + question.size)
        header(out, q, rcode, answers = 0)
        question.copyInto(out, 12)
        return out
    }

    /** Header and question of [response] with the TC (truncated) bit set and no records. */
    fun truncated(response: ByteArray): ByteArray {
        if (response.size < 12) return response
        var p = 12
        repeat(u16(response, 4)) { p = (skipName(response, p) ?: return response.copyOf(12)) + 4 }
        val out = response.copyOf(minOf(p, response.size))
        put16(out, 2, u16(out, 2) or 0x0200)
        put16(out, 6, 0); put16(out, 8, 0); put16(out, 10, 0)
        return out
    }

    /** Copy of [response] carrying [id], for answering from cache or from a DoH reply (sent with id 0). */
    fun withId(response: ByteArray, id: Int): ByteArray = response.copyOf().also { put16(it, 0, id) }

    /** Smallest TTL among answer records, or null if it cannot be determined (then: don't cache). */
    fun minAnswerTtl(r: ByteArray): Int? {
        if (r.size < 12) return null
        if (u16(r, 2) and 0xF != 0) return null              // only cache NOERROR
        val qd = u16(r, 4); val an = u16(r, 6)
        if (an == 0) return null
        var p = 12
        repeat(qd) { p = skipName(r, p) ?: return null; p += 4 }
        var min = Int.MAX_VALUE
        repeat(an) {
            p = skipName(r, p) ?: return null
            if (p + 10 > r.size) return null
            val ttl = ((u16(r, p + 4).toLong() shl 16) or u16(r, p + 6).toLong()).toInt()
            min = minOf(min, ttl.coerceAtLeast(0))
            p += 10 + u16(r, p + 8)
        }
        return if (p <= r.size) min else null
    }

    /**
     * Alias targets (CNAME records) in an answer, lower-case. Trackers "cloaked" as a first-party
     * subdomain (metrics.shop.example -> shop.tracker.example) show up here, so the filter can
     * block the real destination. Malformed input returns what was parsed so far.
     */
    fun cnameTargets(r: ByteArray): List<String> {
        if (r.size < 12) return emptyList()
        val qd = u16(r, 4); val an = u16(r, 6)
        var p = 12
        repeat(qd) { p = (skipName(r, p) ?: return emptyList()) + 4 }
        val out = ArrayList<String>()
        repeat(an) {
            p = skipName(r, p) ?: return out
            if (p + 10 > r.size) return out
            val type = u16(r, p); val rdLen = u16(r, p + 8)
            val rd = p + 10
            if (rd + rdLen > r.size) return out
            if (type == TYPE_CNAME) readName(r, rd)?.let { out += it }
            p = rd + rdLen
        }
        return out
    }

    /**
     * True if [response] is a reply (QR set) to exactly the question in [query]: same question
     * count, name (case-insensitive), type and class. Anything else is dropped rather than
     * cached under the wrong name, which is how cache-poisoning attempts usually look.
     */
    fun answers(query: ByteArray, response: ByteArray): Boolean {
        if (response.size < 12 || query.size < 12) return false
        if (u16(response, 2) and 0x8000 == 0 || u16(response, 4) != 1 || u16(query, 4) != 1) return false
        val qEnd = (skipName(query, 12) ?: return false) + 4
        val rEnd = (skipName(response, 12) ?: return false) + 4
        if (qEnd > query.size || rEnd > response.size || qEnd != rEnd) return false
        for (i in 12 until qEnd) {
            val a = query[i].toInt() and 0xFF
            val b = response[i].toInt() and 0xFF
            if (a != b && Character.toLowerCase(a) != Character.toLowerCase(b)) return false
        }
        return true
    }

    /** Reads a possibly compressed name; null if malformed or looping. */
    private fun readName(m: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var p = start
        var jumps = 0
        while (p < m.size) {
            val len = m[p].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString()
                len and 0xC0 == 0xC0 -> {
                    if (p + 1 >= m.size || ++jumps > 16) return null
                    p = ((len and 0x3F) shl 8) or (m[p + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (p + 1 + len > m.size || len > 63) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..len) sb.append((m[p + i].toInt() and 0xFF).toChar().lowercaseChar())
                    if (sb.length > 253) return null
                    p += 1 + len
                }
            }
        }
        return null
    }

    private fun skipName(m: ByteArray, start: Int): Int? {
        var p = start
        while (p < m.size) {
            val len = m[p].toInt() and 0xFF
            when {
                len == 0 -> return p + 1
                len and 0xC0 == 0xC0 -> return if (p + 2 <= m.size) p + 2 else null
                len and 0xC0 != 0 -> return null
                else -> p += 1 + len
            }
        }
        return null
    }

    private fun header(out: ByteArray, q: DnsQuery, rcode: Int, answers: Int) {
        put16(out, 0, q.id)
        val rd = u16(q.message, 2) and 0x0100
        put16(out, 2, 0x8000 or rd or 0x0080 or rcode)        // QR, copy RD, RA, rcode
        put16(out, 4, 1)
        put16(out, 6, answers)
    }

    fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    internal fun put16(b: ByteArray, p: Int, v: Int) { b[p] = (v shr 8).toByte(); b[p + 1] = v.toByte() }
    private fun put32(b: ByteArray, p: Int, v: Int) { put16(b, p, v ushr 16); put16(b, p + 2, v and 0xFFFF) }
}
