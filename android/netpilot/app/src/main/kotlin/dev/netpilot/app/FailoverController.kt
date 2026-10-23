package dev.netpilot.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.provider.Settings
import android.util.Log
import dev.netpilot.core.Decision
import dev.netpilot.core.DeviceState
import dev.netpilot.core.Diagnoser
import dev.netpilot.core.FailoverPolicy
import dev.netpilot.core.Finding
import dev.netpilot.core.LinkSnapshot
import dev.netpilot.core.Mode
import dev.netpilot.core.ProbeResult
import dev.netpilot.core.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val running: Boolean = false,
    val mode: Mode = Mode.PREFER_WIFI,
    val links: List<LinkSnapshot> = emptyList(),
    val boundId: String? = null,
    val systemDefaultId: String? = null,
    val lastDecision: Decision? = null,
    val findings: List<Finding> = emptyList(),
    val events: List<String> = emptyList(),
    /** Plain-language connection history for the Home screen, newest first. */
    val history: List<HistoryItem> = emptyList(),
)

data class HistoryItem(val time: Long, val text: String)

/**
 * Runs the monitor -> probe -> policy -> bind loop. Evaluates on a timer and
 * immediately when the OS adds or removes a network, so losing the bound network
 * fails over without waiting for the next tick.
 *
 * FAILOVER / NETWORK MANAGEMENT for this app's own traffic. This is not bandwidth bonding.
 */
class FailoverController(context: Context, private val scope: CoroutineScope) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val monitor = NetworkMonitor(cm)
    private val selector = NetworkSelector(cm)
    private val prober = Prober()
    private var policy = FailoverPolicy()
    private val diagnoser = Diagnoser()
    private val probes = HashMap<Network, ProbeResult>()
    private val evalLock = Mutex()
    private var jobs = emptyList<Job>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    var probeIntervalMs = 15_000L
    /** Re-probe interval while the bound link is failing, so failover doesn't wait a full interval. */
    var degradedIntervalMs = 3_000L
    @Volatile private var boundDegraded = false
    private var lastStatus: Map<Transport, LinkHealth> = emptyMap()
    private var lastDefault: Transport? = null

    private enum class LinkHealth { WORKING, NO_INTERNET }

    /** Called (on the main thread) when Wi-Fi is connected but stops reaching the internet. */
    var onWifiBroken: (() -> Unit)? = null

    fun start(mode: Mode, keepCellularUp: Boolean) {
        if (_state.value.running) return
        policy = FailoverPolicy() // forget streaks and the bound id from a previous run
        _state.value = _state.value.copy(running = true, mode = mode)
        monitor.start(keepCellularUp)
        log("started: mode=$mode keepCellularUp=$keepCellularUp")
        jobs = listOf(
            scope.launch { while (true) { evaluate(probe = true); delay(if (boundDegraded) degradedIntervalMs else probeIntervalMs) } },
            // Membership changes (network added/lost) trigger a fast evaluation using cached probes.
            scope.launch {
                monitor.links.distinctUntilChangedBy { it.keys }.drop(1).collect { evaluate(probe = false) }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        monitor.stop()
        selector.bind(null) // hand routing back to the system default
        probes.clear()
        _state.value = _state.value.copy(running = false, boundId = null, links = emptyList())
        log("stopped; process unbound")
    }

    fun setMode(mode: Mode) {
        _state.value = _state.value.copy(mode = mode)
        log("mode -> $mode")
        scope.launch { evaluate(probe = false) }
    }

    fun setKeepCellularUp(enabled: Boolean) = monitor.setKeepCellularUp(enabled)

    /** One-off full diagnosis, independent of the timer. */
    fun diagnoseNow() { scope.launch { evaluate(probe = true) } }

    /** Live OS networks keyed by snapshot id, for components (the downloader) that need Network handles. */
    fun networks(): Map<String, Network> = monitor.links.value.keys.associateBy { it.toString() }

    private suspend fun evaluate(probe: Boolean) = evalLock.withLock {
        val os = monitor.links.value
        probes.keys.retainAll(os.keys)
        if (probe) {
            coroutineScope { os.keys.map { n -> async { n to withTimeoutOrNull(15_000) { prober.probe(n) } } }.awaitAll() }
                .forEach { (n, r) -> if (r != null) probes[n] = r else probes.remove(n) }
        }
        val snaps = os.values.map { it.toSnapshot(probes[it.network]) }
        val mode = _state.value.mode
        val decision = policy.evaluate(mode, snaps)
        boundDegraded = decision is Decision.Stay && snaps.firstOrNull { it.id == decision.linkId }?.probe?.reachable == false

        when (decision) {
            is Decision.Switch -> {
                val target = os.keys.firstOrNull { it.toString() == decision.toId }
                val ok = target != null && selector.bind(target)
                log("${if (decision.failover) "FAILOVER" else "switch"} ${decision.fromId} -> ${decision.toId}: ${decision.reason} (bind=$ok)")
            }
            is Decision.NoUsableLink -> {
                // Unbind so the app at least follows the system default instead of a dead network.
                if (selector.bound != null) { selector.bind(null); log("no usable link (${decision.reason}); unbound") }
            }
            is Decision.Stay -> Unit
        }

        recordHistory(snaps, monitor.systemDefault.value?.let { os[it] }?.caps?.transport())

        _state.value = _state.value.copy(
            links = snaps,
            boundId = selector.bound?.toString(),
            systemDefaultId = monitor.systemDefault.value?.toString(),
            lastDecision = decision,
            findings = diagnoser.diagnose(deviceState(os.values), snaps),
        )
    }

    /** Turns state changes into sentences: "Wi-Fi stopped working", "Phone switched to mobile data". */
    private fun recordHistory(snaps: List<LinkSnapshot>, defaultTransport: Transport?) {
        val now = HashMap<Transport, LinkHealth>()
        for (l in snaps) if (l.transport == Transport.WIFI || l.transport == Transport.CELLULAR) {
            // Only judge links we have probed; before that, trust Android's own check.
            val ok = l.probe?.reachable ?: l.validated
            now[l.transport] = if (ok && !l.captivePortal) LinkHealth.WORKING else LinkHealth.NO_INTERNET
        }
        val items = ArrayList<String>()
        if (lastStatus.isNotEmpty() || lastDefault != null) {
            for (t in listOf(Transport.WIFI, Transport.CELLULAR)) {
                val name = app.getString(if (t == Transport.WIFI) R.string.wifi else R.string.mobile_data)
                val before = lastStatus[t]; val after = now[t]
                if (before == after) continue
                items += when {
                    after == null -> app.getString(R.string.hist_disconnected, name)
                    before == null && after == LinkHealth.WORKING -> app.getString(R.string.hist_connected, name)
                    after == LinkHealth.NO_INTERNET -> {
                        if (t == Transport.WIFI) onWifiBroken?.invoke()
                        app.getString(R.string.hist_no_internet, name)
                    }
                    else -> app.getString(R.string.hist_working_again, name)
                }
            }
            if (defaultTransport != null && defaultTransport != lastDefault &&
                (defaultTransport == Transport.WIFI || defaultTransport == Transport.CELLULAR)) {
                items += app.getString(R.string.hist_now_using,
                    app.getString(if (defaultTransport == Transport.WIFI) R.string.wifi else R.string.mobile_data))
            }
        }
        lastStatus = now
        if (defaultTransport != null) lastDefault = defaultTransport
        if (items.isEmpty()) return
        val t = System.currentTimeMillis()
        _state.value = _state.value.copy(history = (items.map { HistoryItem(t, it) } + _state.value.history).take(100))
    }

    private fun deviceState(links: Collection<OsLink>) = DeviceState(
        // Our own VPN reports our UID as owner; other apps' VPNs hide it (INVALID_UID).
        anyVpnActive = links.any { it.caps?.let { c -> c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.ownerUid != Process.myUid() } == true },
        dataSaverOn = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        backgroundRestricted = app.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted,
        airplaneMode = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
    )

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${TS.format(Date())} $msg"
        _state.value = _state.value.copy(events = (listOf(line) + _state.value.events).take(200))
    }

    companion object {
        private const val TAG = "NetPilot"
        private val TS = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
