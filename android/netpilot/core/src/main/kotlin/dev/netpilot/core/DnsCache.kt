package dev.netpilot.core

/**
 * Small LRU cache of upstream DNS answers keyed by name and type. TTLs are clamped so a
 * misconfigured upstream can neither disable caching nor pin a stale answer for long.
 * Thread-safe.
 */
class DnsCache(
    private val maxEntries: Int = 512,
    private val minTtlSec: Int = 30,
    private val maxTtlSec: Int = 3_600,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val response: ByteArray, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    fun get(name: String, type: Int, id: Int): ByteArray? = synchronized(map) {
        val key = "$name/$type"
        val e = map[key] ?: return null
        if (clock() >= e.expiresAt) { map.remove(key); return null }
        Dns.withId(e.response, id)
    }

    fun put(name: String, type: Int, response: ByteArray) {
        val ttl = Dns.minAnswerTtl(response) ?: return
        val t = ttl.coerceIn(minTtlSec, maxTtlSec)
        synchronized(map) { map["$name/$type"] = Entry(response.copyOf(), clock() + t * 1000L) }
    }

    fun clear() = synchronized(map) { map.clear() }
}
