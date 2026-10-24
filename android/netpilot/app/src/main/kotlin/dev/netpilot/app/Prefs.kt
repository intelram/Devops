package dev.netpilot.app

import android.content.Context
import dev.netpilot.core.Mode
import dev.netpilot.core.SpeedResult
import dev.netpilot.core.UsageState

/** Encrypted-DNS upstreams offered in Settings. All are DNS-over-HTTPS (RFC 8484). */
enum class DnsProvider(val url: String, val title: Int, val detail: Int) {
    CLOUDFLARE("https://cloudflare-dns.com/dns-query", R.string.dns_cloudflare, R.string.dns_cloudflare_detail),
    QUAD9("https://dns.quad9.net/dns-query", R.string.dns_quad9, R.string.dns_quad9_detail),
    GOOGLE("https://dns.google/dns-query", R.string.dns_google, R.string.dns_google_detail),
    MULLVAD("https://dns.mullvad.net/dns-query", R.string.dns_mullvad, R.string.dns_mullvad_detail),
    CUSTOM("", R.string.dns_custom, R.string.dns_custom_detail),
}

/** All user settings. SharedPreferences only; nothing leaves the device. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("netpilot", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Whether the user last left protection on; used to resume after the app or phone restarts. */
    var wantProtection: Boolean
        get() = p.getBoolean("wantProtection", false)
        set(v) = p.edit().putBoolean("wantProtection", v).apply()

    var dnsProvider: DnsProvider
        get() = runCatching { DnsProvider.valueOf(p.getString("dns", null) ?: "") }.getOrDefault(DnsProvider.CLOUDFLARE)
        set(v) = p.edit().putString("dns", v.name).apply()

    var customDnsUrl: String
        get() = p.getString("customDns", "") ?: ""
        set(v) = p.edit().putString("customDns", v).apply()

    val dnsUrl: String get() = dnsProvider.takeIf { it != DnsProvider.CUSTOM }?.url
        ?: customDnsUrl.takeIf { it.startsWith("https://") } ?: DnsProvider.CLOUDFLARE.url

    /** If encrypted DNS fails (e.g. hotel Wi-Fi login pages), use the network's own DNS. */
    var allowPlainFallback: Boolean
        get() = p.getBoolean("plainFallback", true)
        set(v) = p.edit().putBoolean("plainFallback", v).apply()

    /** Also route well-known public DNS servers into the tunnel, for apps that skip the phone's DNS. */
    var catchHardcodedDns: Boolean
        get() = p.getBoolean("catchHardcoded", false)
        set(v) = p.edit().putBoolean("catchHardcoded", v).apply()

    fun listEnabled(id: String): Boolean = p.getBoolean("list_$id", Blocklists.SOURCES.firstOrNull { it.id == id }?.defaultOn ?: true)
    fun setListEnabled(id: String, on: Boolean) = p.edit().putBoolean("list_$id", on).apply()
    fun removeListEnabled(id: String) = p.edit().remove("list_$id").apply()

    /** Links (https) to lists the user added. */
    var customLists: Set<String>
        get() = p.getStringSet("customLists", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("customLists", v).apply()

    /** Let automatic list updates use mobile data (otherwise only Wi-Fi, or when a list is a week old). */
    var updateOnMobile: Boolean
        get() = p.getBoolean("updateOnMobile", false)
        set(v) = p.edit().putBoolean("updateOnMobile", v).apply()

    var allowedDomains: Set<String>
        get() = p.getStringSet("allowed", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("allowed", v).apply()

    var blockedDomains: Set<String>
        get() = p.getStringSet("blocked", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("blocked", v).apply()

    /** Package names that bypass the VPN entirely. */
    var excludedApps: Set<String>
        get() = p.getStringSet("excludedApps", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("excludedApps", v).apply()

    var askedNotifications: Boolean
        get() = p.getBoolean("askedNotif", false)
        set(v) = p.edit().putBoolean("askedNotif", v).apply()

    var keepActivityLog: Boolean
        get() = p.getBoolean("activityLog", true)
        set(v) = p.edit().putBoolean("activityLog", v).apply()

    var connectionAlerts: Boolean
        get() = p.getBoolean("alerts", true)
        set(v) = p.edit().putBoolean("alerts", v).apply()

    var keepCellularUp: Boolean
        get() = p.getBoolean("keepCell", false)
        set(v) = p.edit().putBoolean("keepCell", v).apply()

    var mode: Mode
        get() = runCatching { Mode.valueOf(p.getString("mode", null) ?: "") }.getOrDefault(Mode.AUTO)
        set(v) = p.edit().putString("mode", v.name).apply()

    // Blocklist metadata
    fun listUpdatedAt(id: String): Long = p.getLong("listAt_$id", 0)
    fun listEtag(id: String): String? = p.getString("listEtag_$id", null)
    fun setListMeta(id: String, at: Long, etag: String?) =
        p.edit().putLong("listAt_$id", at).putString("listEtag_$id", etag).apply()

    // Statistics: today's counters reset at local midnight; lifetime counter never resets.
    fun loadStats(): Triple<String, Long, Long> =
        Triple(p.getString("statDay", "") ?: "", p.getLong("statBlockedDay", 0), p.getLong("statCheckedDay", 0))
    val lifetimeBlocked: Long get() = p.getLong("statBlockedAll", 0)
    fun saveStats(day: String, blockedDay: Long, checkedDay: Long, blockedAll: Long) = p.edit()
        .putString("statDay", day).putLong("statBlockedDay", blockedDay)
        .putLong("statCheckedDay", checkedDay).putLong("statBlockedAll", blockedAll).apply()

    /** Last 50 history lines as "time|text", newest first. Stays on the phone. */
    fun loadHistory(): List<HistoryItem> = (p.getString("history", "") ?: "").lines().mapNotNull { line ->
        val i = line.indexOf('|'); if (i <= 0) null else line.substring(0, i).toLongOrNull()?.let { HistoryItem(it, line.substring(i + 1)) }
    }
    fun saveHistory(items: List<HistoryItem>) =
        p.edit().putString("history", items.take(50).joinToString("\n") { "${it.time}|${it.text.replace('\n', ' ')}" }).apply()

    fun loadUsage(): UsageState? = p.getString("usageDay", null)?.let {
        UsageState(it, p.getLong("usageBoot", 0), p.getLong("usageLast", 0), p.getLong("usageToday", 0))
    }
    fun saveUsage(u: UsageState) = p.edit().putString("usageDay", u.day).putLong("usageBoot", u.bootId)
        .putLong("usageLast", u.lastCounter).putLong("usageToday", u.today).apply()

    /** Experimental: spread connections over Wi-Fi and mobile data at the same time. */
    var spreadMode: Boolean
        get() = p.getBoolean("spread", false)
        set(v) = p.edit().putBoolean("spread", v).apply()

    /** "Maximum speed": also split connections to the same server between the networks. */
    var spreadSplitSites: Boolean
        get() = p.getBoolean("spreadSplit", false)
        set(v) = p.edit().putBoolean("spreadSplit", v).apply()

    /** Speed test results, newest first (last 10). */
    fun loadSpeedHistory(): List<SpeedResult> =
        (p.getString("speedHistory", "") ?: "").lines().mapNotNull { SpeedResult.decode(it) }
    fun addSpeedResult(r: SpeedResult) =
        p.edit().putString("speedHistory", (listOf(r) + loadSpeedHistory()).take(10).joinToString("\n") { it.encode() }).apply()
    fun clearSpeedHistory() = p.edit().remove("speedHistory").apply()

    fun clearStats() = p.edit().remove("history").remove("statDay").remove("statBlockedDay").remove("statCheckedDay").remove("statBlockedAll").apply()
}
