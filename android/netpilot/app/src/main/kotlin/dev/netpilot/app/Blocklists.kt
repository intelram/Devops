package dev.netpilot.app

import android.content.Context
import android.util.Log
import dev.netpilot.core.DomainFilter
import dev.netpilot.core.DomainSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ListSource(
    val id: String,
    val url: String,
    val title: Int,
    val detail: Int,
    val defaultOn: Boolean,
    /** Shown in About; the lists are downloaded at runtime and never bundled in the APK. */
    val credit: String,
)

data class ListStatus(val id: String, val domains: Int, val updatedAt: Long, val error: String? = null)

/**
 * Downloads, compiles and loads blocklists, and builds the active [DomainFilter].
 *
 * Integrity and safety: HTTPS only (cleartext is denied app-wide), a size cap, conditional
 * requests with ETag, and the previous compiled list stays in use if a download or parse
 * fails. A list that parses to suspiciously few domains is rejected rather than silently
 * weakening protection.
 */
class Blocklists(private val context: Context, private val prefs: Prefs) {

    companion object {
        private const val TAG = "Blocklists"
        private const val MAX_BYTES = 48L shl 20
        const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000

        val SOURCES = listOf(
            ListSource("ads", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                R.string.list_ads, R.string.list_ads_detail, defaultOn = true,
                credit = "StevenBlack/hosts unified list (CC BY 3.0 / MIT)"),
            ListSource("harmful", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.medium-onlydomains.txt",
                R.string.list_harmful, R.string.list_harmful_detail, defaultOn = true,
                credit = "HaGeZi Threat Intelligence Feeds, medium (GPL-3.0)"),
            ListSource("strict", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
                R.string.list_strict, R.string.list_strict_detail, defaultOn = false,
                credit = "HaGeZi Multi PRO (GPL-3.0)"),
        )
    }

    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val loaded = HashMap<String, DomainSet>()
    private val seed: DomainSet by lazy { loadSeed() }

    @Volatile var filter: DomainFilter = DomainFilter.ALLOW_ALL
        private set

    private val _status = MutableStateFlow<List<ListStatus>>(emptyList())
    val status: StateFlow<List<ListStatus>> = _status

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating

    /** Loads compiled lists from disk and rebuilds the filter. Fast; safe to call on start. */
    @Synchronized
    fun load() {
        for (s in SOURCES) {
            if (loaded.containsKey(s.id)) continue
            val f = File(dir, "${s.id}.bin")
            if (f.exists()) runCatching { f.inputStream().use { DomainSet.readFrom(it) } }
                .onSuccess { loaded[s.id] = it }
                .onFailure { Log.w(TAG, "corrupt ${s.id}, will re-download", it); f.delete(); prefs.setListMeta(s.id, 0, null) }
        }
        rebuild()
    }

    @Synchronized
    fun rebuild() {
        val active = LinkedHashMap<String, DomainSet>()
        for (s in SOURCES) if (prefs.listEnabled(s.id)) loaded[s.id]?.let { active[s.id] = it }
        // Until the main ads list has been downloaded once, use the small built-in list.
        if (prefs.listEnabled("ads") && "ads" !in active) active["seed"] = seed
        filter = DomainFilter(active, prefs.blockedDomains, prefs.allowedDomains)
        publish()
    }

    fun needsUpdate(): Boolean = SOURCES.any {
        prefs.listEnabled(it.id) && System.currentTimeMillis() - prefs.listUpdatedAt(it.id) > UPDATE_EVERY_MS
    }

    /** Blocking network call: run on a background thread. Returns true if anything changed. */
    fun update(force: Boolean): Boolean {
        _updating.value = true
        var changed = false
        val errors = HashMap<String, String>()
        try {
            for (s in SOURCES) {
                if (!prefs.listEnabled(s.id)) continue
                if (!force && System.currentTimeMillis() - prefs.listUpdatedAt(s.id) < UPDATE_EVERY_MS && loaded.containsKey(s.id)) continue
                try {
                    if (download(s, force)) changed = true
                } catch (e: Exception) {
                    Log.w(TAG, "update ${s.id} failed", e)
                    errors[s.id] = e.message ?: e.javaClass.simpleName
                }
            }
            if (changed) rebuild()
        } finally {
            _updating.value = false
            publish(errors)
        }
        return changed
    }

    private fun download(s: ListSource, force: Boolean): Boolean {
        val c = URL(s.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "NetPilot/0.2")
            if (!force || loaded.containsKey(s.id)) prefs.listEtag(s.id)?.let { c.setRequestProperty("If-None-Match", it) }
            when (c.responseCode) {
                304 -> { prefs.setListMeta(s.id, System.currentTimeMillis(), prefs.listEtag(s.id)); return false }
                200 -> Unit
                else -> throw IOException("HTTP ${c.responseCode}")
            }
            if (c.contentLengthLong > MAX_BYTES) throw IOException("list too large")
            val builder = DomainSet.Builder()
            var bytes = 0L
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    bytes += line.length + 1
                    if (bytes > MAX_BYTES) throw IOException("list too large")
                    DomainSet.parseLine(line)?.let(builder::add)
                }
            }
            if (builder.count < 1_000) throw IOException("list looks incomplete (${builder.count} domains)")
            val set = builder.build()
            val tmp = File(dir, "${s.id}.tmp")
            tmp.outputStream().use { set.writeTo(it) }
            if (!tmp.renameTo(File(dir, "${s.id}.bin"))) throw IOException("could not save list")
            synchronized(this) { loaded[s.id] = set }
            prefs.setListMeta(s.id, System.currentTimeMillis(), c.getHeaderField("ETag"))
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun publish(errors: Map<String, String> = emptyMap()) {
        _status.value = SOURCES.map {
            ListStatus(it.id, loaded[it.id]?.size ?: 0, prefs.listUpdatedAt(it.id), errors[it.id])
        }
    }

    private fun loadSeed(): DomainSet = runCatching {
        context.resources.openRawResource(R.raw.seed_blocklist).bufferedReader().useLines { lines ->
            val b = DomainSet.Builder()
            for (l in lines) DomainSet.parseLine(l)?.let(b::add)
            b.build()
        }
    }.getOrDefault(DomainSet.EMPTY)
}
