package dev.netpilot.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Compact, immutable set of domains for blocklists of a few hundred thousand entries.
 * Stores sorted 64-bit FNV-1a hashes (8 bytes per domain instead of a String object each);
 * a false match needs a 64-bit collision, which is negligible at these sizes.
 *
 * [matches] also checks parent domains, so listing `ads.example.com` blocks `x.ads.example.com`.
 */
class DomainSet private constructor(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    fun containsExact(domain: String): Boolean = hashes.binarySearch(hash(domain)) >= 0

    fun matches(domain: String): Boolean {
        var d = domain
        while (true) {
            if (containsExact(d)) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
            if (d.indexOf('.') < 0) return false // never match on a bare TLD such as "com"
        }
    }

    fun writeTo(out: OutputStream) {
        val o = DataOutputStream(out.buffered())
        o.writeInt(MAGIC); o.writeInt(hashes.size)
        for (h in hashes) o.writeLong(h)
        o.flush()
    }

    /** Streams domains into a primitive array, so a 600k-line list never exists as String objects. */
    class Builder {
        private var arr = LongArray(1024)
        private var n = 0
        val count: Int get() = n

        fun add(domain: String) {
            val d = normalize(domain) ?: return
            if (n == arr.size) arr = arr.copyOf(n * 2)
            arr[n++] = hash(d)
        }

        fun build(): DomainSet {
            val a = arr.copyOf(n)
            a.sort()
            return DomainSet(dedupe(a))
        }
    }

    companion object {
        private const val MAGIC = 0x4E50_4453 // "NPDS"
        val EMPTY = DomainSet(LongArray(0))

        fun of(domains: Iterable<String>): DomainSet = Builder().apply { domains.forEach(::add) }.build()

        fun readFrom(input: InputStream): DomainSet {
            val i = DataInputStream(input.buffered())
            if (i.readInt() != MAGIC) throw IOException("not a domain set")
            val n = i.readInt()
            if (n < 0 || n > 10_000_000) throw IOException("bad size $n")
            val arr = LongArray(n) { i.readLong() }
            for (k in 1 until n) if (arr[k - 1] >= arr[k]) throw IOException("unsorted")
            return DomainSet(arr)
        }

        /**
         * Extracts domains from one line of a hosts file ("0.0.0.0 ads.example.com"), a plain
         * domain list ("ads.example.com"), or a basic adblock rule ("||ads.example.com^").
         * Returns null for comments, rules with options, and anything that is not a domain.
         */
        fun parseLine(raw: String): String? {
            var line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null
            if (line.startsWith("||")) {
                if (!line.endsWith("^") || line.contains('$') || line.contains('/') || line.contains('*')) return null
                return normalize(line.substring(2, line.length - 1))
            }
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            line = when (parts.size) {
                1 -> parts[0]
                2 -> if (parts[0] in SINK_IPS) parts[1] else return null
                else -> return null
            }
            if (line in HOSTS_NOISE) return null
            return normalize(line)
        }

        fun normalize(raw: String): String? {
            var d = raw.trim().lowercase().removeSuffix(".")
            if (d.startsWith("*.")) d = d.substring(2)
            if (d.length !in 3..253 || !d.contains('.') || d.startsWith('.') || d.contains("..")) return null
            for (c in d) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_')) return null
            if (d.all { it.isDigit() || it == '.' }) return null // an IPv4 literal, not a domain
            return d
        }

        /** FNV-1a 64-bit over the (already lower-case ASCII) domain. */
        fun hash(d: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in d) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            return h
        }

        private fun dedupe(sorted: LongArray): LongArray {
            if (sorted.isEmpty()) return sorted
            var n = 1
            for (k in 1 until sorted.size) if (sorted[k] != sorted[n - 1]) sorted[n++] = sorted[k]
            return sorted.copyOf(n)
        }

        private val SINK_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0")
        private val HOSTS_NOISE = setOf("localhost", "localhost.localdomain", "local", "broadcasthost",
            "ip6-localhost", "ip6-loopback", "0.0.0.0")
    }
}

enum class BlockReason { LIST, USER_BLOCKED, HIDDEN_TRACKER }

data class Verdict(val blocked: Boolean, val reason: BlockReason? = null, val listId: String? = null, val userAllowed: Boolean = false)

/**
 * The filter decision. Order: the user's allowlist wins, then the user's blocklist, then
 * the enabled lists. A paused filter allows everything.
 */
class DomainFilter(
    private val lists: Map<String, DomainSet>,
    private val userBlocked: Set<String>,
    private val userAllowed: Set<String>,
    private val neverBlock: Set<String> = NEVER_BLOCK,
) {
    val totalDomains: Int get() = lists.values.sumOf { it.size }

    fun check(domain: String): Verdict {
        if (suffixMatch(domain, userAllowed)) return Verdict(false, userAllowed = true)
        if (suffixMatch(domain, userBlocked)) return Verdict(true, BlockReason.USER_BLOCKED)
        // A bad or tampered list must not be able to cut the phone off from its own health checks.
        if (domain in neverBlock) return Verdict(false)
        for ((id, set) in lists) if (set.matches(domain)) return Verdict(true, BlockReason.LIST, id)
        return Verdict(false)
    }

    private fun suffixMatch(domain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var d = domain
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    /**
     * Second look for allowed lookups: if the answer is an alias to a blocked domain (CNAME
     * cloaking), block it as a hidden tracker. Domains the user allowed are never second-guessed.
     */
    fun checkAliases(queried: Verdict, targets: List<String>): Verdict? {
        if (queried.blocked || queried.userAllowed) return null
        for (t in targets) {
            val v = check(t)
            if (v.blocked) return Verdict(true, BlockReason.HIDDEN_TRACKER, v.listId)
        }
        return null
    }

    companion object {
        // Declared before ALLOW_ALL, whose constructor reads it as a default argument.
        /** Exact names the phone needs to judge connectivity, keep time and receive push messages. */
        val NEVER_BLOCK = setOf(
            "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "clients3.google.com",
            "www.google.com", "time.android.com", "time.google.com", "mtalk.google.com",
        )

        val ALLOW_ALL = DomainFilter(emptyMap(), emptySet(), emptySet())
    }
}
