package dev.netpilot.app

import android.content.Context
import dev.netpilot.core.Mode

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

    fun listEnabled(id: String): Boolean = p.getBoolean("list_$id", Blocklists.SOURCES.first { it.id == id }.defaultOn)
    fun setListEnabled(id: String, on: Boolean) = p.edit().putBoolean("list_$id", on).apply()

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

    fun clearStats() = p.edit().remove("statDay").remove("statBlockedDay").remove("statCheckedDay").remove("statBlockedAll").apply()
}
