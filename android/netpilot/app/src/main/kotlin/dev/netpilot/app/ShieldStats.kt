package dev.netpilot.app

import dev.netpilot.core.BlockReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val time: Long, val domain: String, val blocked: Boolean, val reason: BlockReason?, val listId: String?)

data class StatsSnapshot(
    val blockedToday: Long = 0,
    val checkedToday: Long = 0,
    val blockedAllTime: Long = 0,
    val recent: List<LogEntry> = emptyList(),
    val topBlocked: List<Pair<String, Int>> = emptyList(),
)

/**
 * Counts of domain lookups checked and blocked. These are exact counts of DNS requests, not
 * "ads": one blocked domain can stand for zero or many ads, and the UI says so.
 * The activity list lives in memory only and is cleared when the app process ends.
 */
class ShieldStats(private val prefs: Prefs) {
    private val lock = Any()
    private var day = ""
    private var blockedDay = 0L
    private var checkedDay = 0L
    private var blockedAll = 0L
    private val recent = ArrayDeque<LogEntry>()
    private val top = HashMap<String, Int>()
    private var dirty = false

    private val _state = MutableStateFlow(StatsSnapshot())
    val state: StateFlow<StatsSnapshot> = _state

    init {
        val (d, b, c) = prefs.loadStats()
        day = d; blockedDay = b; checkedDay = c; blockedAll = prefs.lifetimeBlocked
        rollDay()
        publish()
    }

    fun record(domain: String, blocked: Boolean, reason: BlockReason?, listId: String?) {
        synchronized(lock) {
            rollDay()
            checkedDay++
            if (blocked) {
                blockedDay++; blockedAll++
                if (top.size < 5_000 || domain in top) top[domain] = (top[domain] ?: 0) + 1
            }
            if (prefs.keepActivityLog) {
                // Collapse repeats of the same lookup so the list stays readable.
                val last = recent.firstOrNull()
                if (last == null || last.domain != domain || last.blocked != blocked) {
                    recent.addFirst(LogEntry(System.currentTimeMillis(), domain, blocked, reason, listId))
                    while (recent.size > 300) recent.removeLast()
                }
            }
            dirty = true
        }
    }

    /** Called about once a second by the service; publishes and persists only when something changed. */
    fun flush(persist: Boolean) {
        val changed = synchronized(lock) { dirty.also { dirty = false } }
        if (!changed) return
        publish()
        if (persist) synchronized(lock) { prefs.saveStats(day, blockedDay, checkedDay, blockedAll) }
    }

    fun clear() {
        synchronized(lock) { blockedDay = 0; checkedDay = 0; blockedAll = 0; recent.clear(); top.clear() }
        prefs.clearStats()
        publish()
    }

    fun clearLog() { synchronized(lock) { recent.clear() }; publish() }

    private fun rollDay() {
        val today = DAY.format(Date())
        if (today != day) { day = today; blockedDay = 0; checkedDay = 0; top.clear() }
    }

    private fun publish() {
        _state.value = synchronized(lock) {
            StatsSnapshot(blockedDay, checkedDay, blockedAll, recent.toList(),
                top.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value })
        }
    }

    companion object {
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
