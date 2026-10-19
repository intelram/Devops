package dev.netpilot.core

data class PolicyConfig(
    /** Consecutive unusable evaluations before we abandon the current link. */
    val failThreshold: Int = 2,
    /** Consecutive usable evaluations before a non-current link is trusted for fail-back. */
    val recoverThreshold: Int = 3,
    /** AUTO mode: a candidate must beat the current link's score by this much. */
    val switchMargin: Int = 15,
    /** Minimum time on a link before a voluntary (non-failure) switch. */
    val minDwellMs: Long = 30_000,
)

sealed interface Decision {
    data class Stay(val linkId: String, val reason: String) : Decision
    data class Switch(val fromId: String?, val toId: String, val reason: String, val failover: Boolean) : Decision
    data class NoUsableLink(val reason: String) : Decision
}

/**
 * Hysteresis state machine choosing which single network this app binds to.
 * Fails over fast (failThreshold), fails back slowly (recoverThreshold + minDwellMs)
 * so a flapping Wi-Fi link does not cause constant reconnects.
 *
 * Not thread-safe; drive it from one coroutine.
 */
class FailoverPolicy(
    private val config: PolicyConfig = PolicyConfig(),
    private val scorer: LinkScorer = LinkScorer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var currentId: String? = null
        private set
    private var switchedAtMs = 0L
    private val goodStreak = HashMap<String, Int>()
    private val badStreak = HashMap<String, Int>()

    fun evaluate(mode: Mode, links: List<LinkSnapshot>): Decision {
        updateStreaks(links)
        val allowed = links.filter { allowedIn(mode, it.transport) }
        val usableNow = allowed.filter { scorer.isUsable(it) }
        val current = allowed.firstOrNull { it.id == currentId }

        val currentFailed = current == null || (badStreak[current.id] ?: 0) >= config.failThreshold
        if (currentFailed) {
            val target = rank(mode, usableNow).firstOrNull()
                ?: return Decision.NoUsableLink(
                    if (allowed.isEmpty()) "no ${modeLabel(mode)} network present" else "no allowed network passes probes",
                ).also { if (current == null) currentId = null }
            val reason = when {
                currentId == null -> "initial selection"
                current == null -> "current network lost or disallowed by mode"
                else -> "current network failed ${config.failThreshold} consecutive checks"
            }
            return switchTo(target, reason, failover = currentId != null)
        }

        // Current link is healthy. Consider a voluntary switch.
        if (clock() - switchedAtMs < config.minDwellMs) return Decision.Stay(current!!.id, "within dwell time")
        val trusted = usableNow.filter { it.id != current!!.id && (goodStreak[it.id] ?: 0) >= config.recoverThreshold }

        preferredTransport(mode)?.let { pref ->
            if (current!!.transport != pref) {
                trusted.filter { it.transport == pref }.maxByOrNull(scorer::score)?.let {
                    return switchTo(it, "fail-back to preferred ${pref.name.lowercase()}", failover = false)
                }
            }
            return Decision.Stay(current.id, "on preferred transport or preferred not yet stable")
        }

        if (mode == Mode.AUTO) {
            val curScore = scorer.score(current!!)
            val best = trusted.maxByOrNull(scorer::score)
            if (best != null && scorer.score(best) >= curScore + config.switchMargin) {
                return switchTo(best, "score ${scorer.score(best)} beats current $curScore by ≥${config.switchMargin}", failover = false)
            }
        }
        return Decision.Stay(current!!.id, "current network healthy")
    }

    private fun switchTo(target: LinkSnapshot, reason: String, failover: Boolean): Decision {
        val from = currentId
        currentId = target.id
        switchedAtMs = clock()
        return if (from == target.id) Decision.Stay(target.id, reason)
        else Decision.Switch(from, target.id, reason, failover)
    }

    private fun updateStreaks(links: List<LinkSnapshot>) {
        val present = links.map { it.id }.toSet()
        goodStreak.keys.retainAll(present)
        badStreak.keys.retainAll(present)
        for (l in links) {
            if (scorer.isUsable(l)) {
                goodStreak[l.id] = (goodStreak[l.id] ?: 0) + 1
                badStreak[l.id] = 0
            } else {
                badStreak[l.id] = (badStreak[l.id] ?: 0) + 1
                goodStreak[l.id] = 0
            }
        }
    }

    private fun rank(mode: Mode, links: List<LinkSnapshot>): List<LinkSnapshot> {
        val pref = preferredTransport(mode)
        return links.sortedWith(
            compareByDescending<LinkSnapshot> { pref != null && it.transport == pref }
                .thenByDescending(scorer::score),
        )
    }

    private fun preferredTransport(mode: Mode): Transport? = when (mode) {
        Mode.PREFER_WIFI -> Transport.WIFI
        Mode.PREFER_CELLULAR -> Transport.CELLULAR
        else -> null
    }

    private fun allowedIn(mode: Mode, t: Transport): Boolean = when (mode) {
        Mode.WIFI_ONLY -> t == Transport.WIFI
        Mode.CELLULAR_ONLY -> t == Transport.CELLULAR
        else -> t != Transport.VPN // VPN networks are reported but never selected directly
    }

    private fun modeLabel(mode: Mode) = when (mode) {
        Mode.WIFI_ONLY -> "Wi-Fi"
        Mode.CELLULAR_ONLY -> "cellular"
        else -> "allowed"
    }
}
