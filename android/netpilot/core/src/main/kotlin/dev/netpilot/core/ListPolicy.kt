package dev.netpilot.core

/** When to download a blocklist, and whether a downloaded copy is trustworthy enough to use. */
object ListPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** On mobile data (unless allowed) a list is refreshed only once it is this old. */
    const val STALE_ON_MOBILE_MS = 7 * DAY_MS

    /**
     * [force] is the user's "Update now". [sparing] means automatic update on mobile data the
     * user hasn't allowed. A list that was never downloaded is always fetched.
     */
    fun shouldFetch(force: Boolean, have: Boolean, ageMs: Long, sparing: Boolean): Boolean = when {
        force || !have -> true
        ageMs < DAY_MS -> false
        sparing -> ageMs >= STALE_ON_MOBILE_MS
        else -> true
    }

    /**
     * Null if a freshly parsed list may replace the previous one, else the reason not to. A list
     * that suddenly loses most of its entries is more likely broken or tampered with than
     * cleaned up, so the previous copy stays in use rather than quietly weakening protection.
     */
    fun rejectReason(newCount: Int, previousCount: Int, minDomains: Int): String? = when {
        newCount < minDomains -> "list looks incomplete ($newCount domains)"
        previousCount >= 1_000 && newCount < previousCount / 2 -> "list shrank from $previousCount to $newCount domains"
        else -> null
    }
}
