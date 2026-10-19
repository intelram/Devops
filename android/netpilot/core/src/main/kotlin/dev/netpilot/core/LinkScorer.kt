package dev.netpilot.core

import kotlin.math.ln
import kotlin.math.min

/**
 * Scores a link 0..100. 0 means unusable. The score is only used to rank links
 * for failover; it is not a throughput prediction.
 */
class LinkScorer(private val meteredPenalty: Int = 10) {

    fun isUsable(link: LinkSnapshot): Boolean {
        if (link.captivePortal) return false
        val probe = link.probe
        // With no probe yet, trust the OS validation result so start-up isn't blocked.
        return probe?.reachable ?: link.validated
    }

    fun score(link: LinkSnapshot): Int {
        if (!isUsable(link)) return 0
        var s = 60
        link.probe?.httpsMs?.let { s -= min(40, (it / 25).toInt()) }         // -1 per 25 ms RTT, max -40
        link.estDownKbps?.takeIf { it > 0 }?.let {
            s += min(20, (ln(it / 1000.0 + 1) * 5).toInt())                   // log-scaled bandwidth bonus
        }
        link.signalDbm?.let { if (it < -100) s -= 10 else if (it > -70) s += 5 }
        if (link.metered) s -= meteredPenalty
        if (!link.validated) s -= 10
        return s.coerceIn(1, 100)
    }
}
