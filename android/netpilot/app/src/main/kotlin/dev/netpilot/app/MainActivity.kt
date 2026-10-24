package dev.netpilot.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.netpilot.core.BlockReason
import dev.netpilot.core.Decision
import dev.netpilot.core.DomainSet
import dev.netpilot.core.Finding
import dev.netpilot.core.FixAction
import dev.netpilot.core.LinkSnapshot
import dev.netpilot.core.Mode
import dev.netpilot.core.Plain
import dev.netpilot.core.Quality
import dev.netpilot.core.Severity
import dev.netpilot.core.Transport
import dev.netpilot.core.qualityOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Single-screen app with four tabs: Home (status at a glance), Protection (block lists and
 * exceptions), Connection (what's wrong and how to fix it) and Settings. Technical controls
 * live under "Show advanced options" on the Connection tab.
 *
 * Built only on framework APIs (no AndroidX), so it builds without Google's Maven repository.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_CONNECT = "connect"
        const val EXTRA_TAB = "tab"
        const val TAB_NETWORK = 2
        private const val REQ_VPN = 1
        private const val REQ_NOTIF = 2
        private const val REQ_ONBOARD = 3
        private const val STATE_TAB = "tab"
    }

    private val app get() = application as NetPilotApp
    private val prefs get() = app.prefs
    private val ui = MainScope()
    private var jobs = emptyList<Job>()
    private val nf = NumberFormat.getIntegerInstance()

    private lateinit var tabs: List<View>
    private lateinit var navItems: List<View>
    private var currentTab = 0
    private var historyExpanded = false
    private var lastTx = -1L
    private var lastRx = -1L
    private var lastSpeedAt = 0L
    private val listSwitches = HashMap<String, MutableList<Switch>>()
    private var syncingSwitches = false
    private var shownBlocked = -1L
    private var lastUsageSample = 0L
    /** Connect was requested while the notification prompt was showing; continue when it closes. */
    private var connectAfterPermission = false
    private var speedJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))

        tabs = listOf(R.id.tab_home, R.id.tab_protection, R.id.tab_network, R.id.tab_settings).map { findViewById(it) }
        navItems = listOf(R.id.nav_home, R.id.nav_protection, R.id.nav_network, R.id.nav_settings).map { findViewById(it) }
        val navIcons = listOf(R.drawable.ic_home, R.drawable.ic_shield, R.drawable.ic_swap, R.drawable.ic_settings)
        val navLabels = listOf(R.string.nav_home, R.string.nav_protection, R.string.nav_network, R.string.nav_settings)
        navItems.forEachIndexed { i, v ->
            v.findViewById<ImageView>(R.id.nav_icon).setImageResource(navIcons[i])
            v.findViewById<TextView>(R.id.nav_label).setText(navLabels[i])
            v.setOnClickListener { selectTab(i) }
            v.tooltipText = getString(navLabels[i])
        }

        setupHome(); setupProtection(); setupNetwork(); setupSettings()
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: 0)

        CrashLog.pending(this)?.let(::showCrashReport)
        if (!prefs.onboarded) startActivityForResult(Intent(this, OnboardingActivity::class.java), REQ_ONBOARD)
        else if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) requestConnect()
        if (savedInstanceState == null) intent?.let(::handleLinkIntent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONNECT, false)) requestConnect()
        handleLinkIntent(intent)
    }

    /** A link shared from another app goes into Fast download (never started without a tap). */
    private fun handleLinkIntent(i: Intent) {
        if (i.hasExtra(EXTRA_TAB)) selectTab(i.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 3))
        if (i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.take(4_096) ?: return
        val link = Regex("""https://[^\s"'<>]+""").find(text)?.value ?: run { toast(getString(R.string.fd_https_only)); return }
        selectTab(TAB_NETWORK)
        findViewById<EditText>(R.id.fd_url).setText(link)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_TAB, currentTab)
    }

    override fun onStart() {
        super.onStart()
        app.screenVisible(true)
        val c = app.controller
        jobs = listOf(
            ui.launch {
                combine(ShieldVpnService.state, ShieldVpnService.pausedUntil, ShieldVpnService.plainFallbackActive, ShieldVpnService.alwaysOn) { _, _, _, _ -> }
                    .collect { renderShield() }
            },
            ui.launch { c.state.collect { renderConnection() } },
            ui.launch { app.stats.state.collect { renderStats() } },
            ui.launch { combine(app.blocklists.status, app.blocklists.updating) { _, _ -> }.collect { renderLists() } },
            ui.launch { while (isActive) { renderSpeedAndUsage(); delay(1_000) } },
            ui.launch { combine(ShieldVpnService.spread, ShieldVpnService.state) { _, _ -> }.collect { renderBoth() } },
            ui.launch { FastDownloadService.state.collect { renderFastDownload() } },
        )
        renderSpeedResult()
        app.blocklists.load()
        renderSettings()
    }

    override fun onResume() {
        super.onResume()
        renderUserLists() // the app picker may have changed exclusions
    }

    override fun onStop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        app.screenVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    @Deprecated("Framework Activity API; AndroidX is not used in this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) connectNow() else dialog(R.string.vpn_denied_title, getString(R.string.vpn_denied_text))
            REQ_ONBOARD -> { prefs.onboarded = true; if (resultCode == RESULT_OK) requestConnect() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_NOTIF && connectAfterPermission) requestConnect()
    }

    // ---------------------------------------------------------------- navigation

    private fun selectTab(i: Int) {
        currentTab = i
        tabs.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            tabs[i].alpha = 0f
            tabs[i].animate().alpha(1f).setDuration(180).start()
        }
        navItems.forEachIndexed { k, v ->
            val sel = k == i
            val color = getColor(if (sel) R.color.primary else R.color.text_secondary)
            v.findViewById<ImageView>(R.id.nav_icon).apply {
                setColorFilter(color)
                background = if (sel) getDrawable(R.drawable.nav_pill) else null
            }
            v.findViewById<TextView>(R.id.nav_label).apply { setTextColor(color); paint.isFakeBoldText = sel; invalidate() }
            v.isSelected = sel
        }
        if (i == 1) renderActivity()
    }

    // ---------------------------------------------------------------- connect flow

    private fun requestConnect() {
        // Ask one thing at a time: two system prompts at once can cancel each other.
        if (Build.VERSION.SDK_INT >= 33 && !connectAfterPermission &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.askedNotifications) {
            prefs.askedNotifications = true
            connectAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        connectAfterPermission = false
        val consent = VpnService.prepare(this)
        if (consent == null) { connectNow(); return }
        val proceed = {
            try { startActivityForResult(consent, REQ_VPN) } catch (e: ActivityNotFoundException) { toast(e.message) }
        }
        if (app.controller.state.value.findings.any { it.code == "VPN_ACTIVE" }) {
            AlertDialog.Builder(this).setTitle(R.string.other_vpn_title).setMessage(R.string.other_vpn_text)
                .setPositiveButton(R.string.btn_connect) { _, _ -> proceed() }
                .setNegativeButton(R.string.cancel, null).show()
        } else {
            proceed()
        }
    }

    private fun connectNow() = ShieldVpnService.send(this, ShieldVpnService.ACTION_CONNECT)

    private fun onConnectTapped() {
        when (ShieldVpnService.state.value) {
            ShieldState.OFF -> requestConnect()
            ShieldState.CONNECTING -> Unit
            ShieldState.ON, ShieldState.PAUSED ->
                if (ShieldVpnService.alwaysOn.value) {
                    AlertDialog.Builder(this).setMessage(R.string.always_on_active)
                        .setPositiveButton(R.string.always_on_button) { _, _ -> open(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        .setNegativeButton(R.string.cancel, null).show()
                } else {
                    ShieldVpnService.send(this, ShieldVpnService.ACTION_DISCONNECT)
                }
        }
    }

    // ---------------------------------------------------------------- Home

    private fun setupHome() {
        findViewById<ConnectButton>(R.id.connect_button).setOnClickListener { onConnectTapped() }
        findViewById<Button>(R.id.resume_button).setOnClickListener { ShieldVpnService.resumeNow() }
        findViewById<ImageButton>(R.id.using_info).apply {
            setOnClickListener { dialog(R.string.bonding_title, getString(R.string.bonding_text)) }
            tooltipText = getString(R.string.bonding_title)
        }
        findViewById<ImageButton>(R.id.blocked_info).apply {
            setOnClickListener { dialog(R.string.blocked_info_title, getString(R.string.blocked_info_text)) }
            tooltipText = getString(R.string.blocked_info_title)
        }
        bindListSwitch("ads", findViewById(R.id.home_ads_switch))
        bindListSwitch("harmful", findViewById(R.id.home_harmful_switch))
        findViewById<Button>(R.id.battery_fix).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.history_more).setOnClickListener { historyExpanded = !historyExpanded; renderConnection() }
        card(R.id.card_wifi, R.drawable.ic_wifi, R.string.wifi)
        card(R.id.card_cell, R.drawable.ic_cell, R.string.mobile_data)
        statLabel(R.id.stat_down, R.string.speed_down)
        statLabel(R.id.stat_up, R.string.speed_up)
        statLabel(R.id.stat_response, R.string.speed_response)

        findViewById<ImageButton>(R.id.both_info).apply {
            setOnClickListener { dialog(R.string.bonding_title, getString(R.string.bonding_text)) }
            tooltipText = getString(R.string.bonding_title)
        }
        findViewById<Switch>(R.id.both_switch).apply {
            isChecked = prefs.spreadMode
            setOnCheckedChangeListener { sw, on ->
                if (!on) { setSpread(false); return@setOnCheckedChangeListener }
                if (prefs.spreadMode) return@setOnCheckedChangeListener
                sw.isChecked = false // only after the user has read what it does
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.both_confirm_title).setMessage(R.string.both_confirm_text)
                    .setPositiveButton(R.string.turn_on) { _, _ -> setSpread(true); sw.isChecked = true }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
        findViewById<Switch>(R.id.both_split_switch).apply {
            isChecked = prefs.spreadSplitSites
            setOnCheckedChangeListener { _, on -> prefs.spreadSplitSites = on } // read per new connection: no reconnect
        }
        findViewById<Button>(R.id.st_run).setOnClickListener { askSpeedTest() }
        findViewById<Button>(R.id.st_history).setOnClickListener { showSpeedHistory() }
    }

    private fun setSpread(on: Boolean) {
        if (prefs.spreadMode == on) return
        prefs.spreadMode = on
        app.controller.setKeepCellularUp(app.keepCellular)
        if (ShieldVpnService.state.value != ShieldState.OFF) ShieldVpnService.send(this, ShieldVpnService.ACTION_RECONNECT)
        renderBoth()
        renderConnection()
    }

    private fun renderBoth() {
        val use = ShieldVpnService.spread.value
        val on = prefs.spreadMode
        val shieldOn = ShieldVpnService.state.value != ShieldState.OFF
        val wifi = use?.get(SpreadDialer.WIFI)
        val cell = use?.get(SpreadDialer.CELL)
        val bothUp = linkOf(Transport.WIFI)?.validated == true && linkOf(Transport.CELLULAR)?.validated == true
        findViewById<TextView>(R.id.both_detail).setText(when {
            !on -> R.string.both_off
            !shieldOn -> R.string.both_needs_protection
            !bothUp -> R.string.both_waiting
            else -> R.string.both_active
        })
        findViewById<View>(R.id.both_split_switch).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.both_split_note).visibility = if (on) View.VISIBLE else View.GONE
        val show = use != null && use.isNotEmpty()
        findViewById<View>(R.id.both_bar).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.both_split).visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        // Bar: share of traffic so far on each network (at least a sliver, so both colours stay visible).
        val wTotal = wifi?.totalBytes ?: 0
        val cTotal = cell?.totalBytes ?: 0
        val sum = (wTotal + cTotal).coerceAtLeast(1)
        (findViewById<View>(R.id.both_bar_wifi).layoutParams as LinearLayout.LayoutParams).weight = (wTotal * 100f / sum).coerceAtLeast(1f)
        (findViewById<View>(R.id.both_bar_cell).layoutParams as LinearLayout.LayoutParams).weight = (cTotal * 100f / sum).coerceAtLeast(1f)
        findViewById<View>(R.id.both_bar).requestLayout()
        val lines = listOfNotNull(wifi?.let { SpreadDialer.WIFI to it }, cell?.let { SpreadDialer.CELL to it },
            use[SpreadDialer.WIRED]?.let { SpreadDialer.WIRED to it }).map { (id, u) ->
            val name = getString(when (id) { SpreadDialer.CELL -> R.string.mobile_data; SpreadDialer.WIRED -> R.string.ethernet; else -> R.string.wifi })
            getString(R.string.both_split_line, name, bitrate(u.downBps * 8.0), u.connections)
        }
        findViewById<TextView>(R.id.both_split).text = lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- Speed test

    private fun askSpeedTest() {
        if (speedJob?.isActive == true) return
        if (linkOf(Transport.CELLULAR)?.validated == true) {
            AlertDialog.Builder(this).setTitle(R.string.st_title).setMessage(R.string.st_mobile_confirm)
                .setPositiveButton(R.string.st_run) { _, _ -> runSpeedTest() }
                .setNegativeButton(R.string.cancel, null).show()
        } else runSpeedTest()
    }

    private fun runSpeedTest() {
        val btn = findViewById<Button>(R.id.st_run)
        val out = findViewById<TextView>(R.id.st_result)
        btn.isEnabled = false
        btn.setText(R.string.st_running)
        // App scope: the test finishes and is saved even if this screen is closed.
        speedJob = app.scope.launch {
            val r = runCatching {
                SpeedTester(getSystemService(android.net.ConnectivityManager::class.java)).run { p ->
                    val live = p.mbps?.let { bitrate(it * 1e6) } ?: "…"
                    val name = p.transport?.let(::transportName) ?: ""
                    val text = when (p.step) {
                        SpeedTester.Step.PING -> getString(R.string.st_step_ping, name)
                        SpeedTester.Step.DOWNLOAD -> getString(R.string.st_step_down, name, live)
                        SpeedTester.Step.UPLOAD -> getString(R.string.st_step_up, name, live)
                        SpeedTester.Step.BOTH -> getString(R.string.st_step_both, live)
                    }
                    runOnUiThread { out.text = text }
                }
            }
            r.onSuccess { prefs.addSpeedResult(it) }
            runOnUiThread {
                btn.isEnabled = true
                btn.setText(R.string.st_run)
                renderSpeedResult()
                r.onFailure { out.text = getString(R.string.st_failed, it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    private fun speedText(r: dev.netpilot.core.SpeedResult): String {
        fun mbps(v: Double?) = v?.let { bitrate(it * 1e6) } ?: getString(R.string.st_unknown)
        fun ping(v: Int?) = v?.let { getString(R.string.st_ping, it) } ?: getString(R.string.st_unknown)
        val lines = ArrayList<String>()
        r.wifi?.let { lines += getString(R.string.st_line, getString(R.string.wifi), mbps(it.downMbps), mbps(it.upMbps), ping(it.pingMs)) }
        r.cell?.let { lines += getString(R.string.st_line, getString(R.string.mobile_data), mbps(it.downMbps), mbps(it.upMbps), ping(it.pingMs)) }
        r.bothDownMbps?.let { both ->
            lines += getString(R.string.st_both_line, mbps(both))
            val best = listOfNotNull(r.wifi?.downMbps, r.cell?.downMbps).maxOrNull()
            if (best != null && best > 0 && both > best) lines += getString(R.string.st_both_gain, ((both / best - 1) * 100).toInt())
        }
        lines += getString(R.string.st_when, DateUtils.getRelativeTimeSpanString(r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        return lines.joinToString("\n")
    }

    private fun renderSpeedResult() {
        if (speedJob?.isActive == true) return
        val last = prefs.loadSpeedHistory().firstOrNull()
        findViewById<TextView>(R.id.st_result).text = last?.let(::speedText) ?: getString(R.string.st_none)
        findViewById<Button>(R.id.st_history).visibility = if (last != null) View.VISIBLE else View.GONE
    }

    private fun showSpeedHistory() {
        val all = prefs.loadSpeedHistory()
        AlertDialog.Builder(this).setTitle(R.string.st_history)
            .setMessage(all.joinToString("\n\n") { speedText(it) })
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.st_clear) { _, _ -> prefs.clearSpeedHistory(); renderSpeedResult() }
            .show()
    }

    private fun card(id: Int, icon: Int, name: Int) {
        val v = findViewById<View>(id)
        v.findViewById<ImageView>(R.id.net_icon).apply { setImageResource(icon); setColorFilter(getColor(R.color.primary)) }
        v.findViewById<TextView>(R.id.net_name).setText(name)
    }

    private fun statLabel(id: Int, label: Int) = findViewById<View>(id).findViewById<TextView>(R.id.stat_label).setText(label)

    private fun statValue(id: Int, value: String) {
        val v = findViewById<View>(id)
        val tv = v.findViewById<TextView>(R.id.stat_value)
        if (tv.text.toString() == value) return // no redraw or accessibility event when nothing changed
        tv.text = value
        v.contentDescription = "${v.findViewById<TextView>(R.id.stat_label).text}: $value"
    }

    private fun renderShield() {
        val s = ShieldVpnService.state.value
        findViewById<ConnectButton>(R.id.connect_button).state = s
        val title = findViewById<TextView>(R.id.status_title)
        val detail = findViewById<TextView>(R.id.status_detail)
        findViewById<Button>(R.id.resume_button).visibility = if (s == ShieldState.PAUSED) View.VISIBLE else View.GONE
        when (s) {
            ShieldState.OFF -> { title.setText(R.string.status_off_title); detail.setText(R.string.status_off_detail) }
            ShieldState.CONNECTING -> { title.setText(R.string.status_connecting_title); detail.text = "" }
            ShieldState.ON -> {
                title.setText(R.string.status_on_title)
                detail.setText(if (ShieldVpnService.plainFallbackActive.value) R.string.status_on_detail_plain else R.string.status_on_detail)
            }
            ShieldState.PAUSED -> {
                title.setText(R.string.status_paused_title)
                detail.text = getString(R.string.status_paused_detail,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ShieldVpnService.pausedUntil.value)))
            }
        }
        findViewById<Button>(R.id.pause_button).isEnabled = s == ShieldState.ON
        findViewById<TextView>(R.id.home_block_note).setText(if (s == ShieldState.OFF) R.string.home_block_note_off else R.string.home_block_note_on)
        renderSettings()
        renderConnection()
    }

    private fun linkOf(t: Transport): LinkSnapshot? = app.controller.state.value.links.firstOrNull { it.transport == t }

    private fun qualityText(q: Quality) = getString(when (q) {
        Quality.EXCELLENT -> R.string.quality_excellent
        Quality.GOOD -> R.string.quality_good
        Quality.FAIR -> R.string.quality_fair
        Quality.POOR -> R.string.quality_poor
        Quality.NOT_WORKING -> R.string.quality_not_working
    })

    /** Status words and chip colour for one network; the colour always comes with words. */
    private fun netStatus(l: LinkSnapshot?): Pair<String, Int> {
        if (l == null) return getString(R.string.net_off) to R.drawable.chip_bad
        if (l.captivePortal) return getString(R.string.net_sign_in) to R.drawable.chip_warn
        if (l.probe == null && !l.validated) return getString(R.string.net_checking) to R.drawable.chip_warn
        val usable = l.probe?.reachable ?: l.validated
        if (!usable) return getString(R.string.net_no_internet) to R.drawable.chip_bad
        val q = qualityOf(true, l.probe?.httpsMs)
        val chip = if (q == Quality.POOR || q == Quality.FAIR) R.drawable.chip_warn else R.drawable.chip_good
        return getString(R.string.net_status_line, getString(R.string.net_working), qualityText(q)) to chip
    }

    private fun chipTextColor(chip: Int) = getColor(when (chip) {
        R.drawable.chip_good -> R.color.good
        R.drawable.chip_warn -> R.color.warn
        else -> R.color.bad
    })

    private fun userProblems(): List<Finding> {
        val list = app.controller.state.value.findings
            .filter { it.severity != Severity.INFO }
            .filterNot { it.code == "VPN_ACTIVE" && ShieldVpnService.state.value != ShieldState.OFF }
        return if (ShieldVpnService.state.value != ShieldState.OFF && strictPrivateDns()) {
            listOf(Finding(Severity.ERROR, "PRIVATE_DNS_BYPASS", null, "", "")) + list
        } else list
    }

    /**
     * Private DNS set to a specific provider sends lookups straight to that provider, around
     * NetPilot, so blocking would silently do nothing. A non-null server name means strict mode.
     */
    private fun strictPrivateDns(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm.activeNetwork?.let { cm.getLinkProperties(it)?.privateDnsServerName } != null
    }

    private fun renderConnection() {
        val st = app.controller.state.value
        for ((cardId, t) in listOf(R.id.card_wifi to Transport.WIFI, R.id.card_cell to Transport.CELLULAR)) {
            val (text, chip) = netStatus(linkOf(t))
            val card = findViewById<View>(cardId)
            card.findViewById<TextView>(R.id.net_status).apply { this.text = text; setBackgroundResource(chip); setTextColor(chipTextColor(chip)) }
            card.contentDescription = "${transportName(t)}: $text"
        }

        val def = physicalDefault(st)
        val spreading = ShieldVpnService.spread.value != null
        if (spreading) {
            findViewById<TextView>(R.id.using_text).setText(R.string.using_both)
        } else findViewById<TextView>(R.id.using_text).setText(when (def?.transport) {
            Transport.WIFI -> R.string.using_wifi
            Transport.CELLULAR -> R.string.using_mobile
            null -> R.string.using_none
            Transport.ETHERNET -> R.string.using_wired
            else -> R.string.using_other
        })
        findViewById<TextView>(R.id.mode_chip).text = if (spreading) getString(R.string.mode_spread) else modeText(def?.transport, st.history)
        findViewById<TextView>(R.id.using_detail).setText(when {
            !st.running -> R.string.using_detail_monitor_off
            linkOf(Transport.WIFI) != null && linkOf(Transport.CELLULAR) != null -> R.string.using_detail_backup
            else -> R.string.using_detail_single
        })
        statValue(R.id.stat_response, def?.probe?.httpsMs?.let { "$it ms" } ?: getString(R.string.speed_unknown))

        val problems = userProblems()
        val pc = findViewById<LinearLayout>(R.id.problems_container)
        pc.removeAllViews()
        for (f in problems.take(3)) pc.addView(problemRow(f))
        findViewById<View>(R.id.problems_card).visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE

        val hc = findViewById<LinearLayout>(R.id.history_container)
        hc.removeAllViews()
        val tf = android.text.format.DateFormat.getTimeFormat(this)
        val shown = if (historyExpanded) st.history.take(30) else st.history.take(4)
        if (shown.isEmpty()) hc.addView(row(getString(R.string.history_empty), null, null, null))
        for (h in shown) hc.addView(row(h.text, tf.format(Date(h.time)), null, null))
        findViewById<Button>(R.id.history_more).apply {
            visibility = if (st.history.size > 4) View.VISIBLE else View.GONE
            setText(if (historyExpanded) R.string.history_less else R.string.history_more)
        }
        renderNetworkTab()
        renderBoth()
    }

    /**
     * The real connection underneath. While protection is on, Android may report NetPilot's own
     * VPN as the default network; the user cares about Wi-Fi / mobile data / wired, so look past it.
     */
    private fun physicalDefault(st: UiState): LinkSnapshot? {
        val physical = st.links.filter { it.transport != Transport.VPN }
        physical.firstOrNull { it.id == st.systemDefaultId }?.let { return it }
        return physical.firstOrNull { it.probe?.reachable ?: it.validated }
            ?: physical.firstOrNull { it.transport == Transport.WIFI }
            ?: physical.firstOrNull()
    }

    /** Plain answer to "is my traffic combined?": never, and say when the phone last switched. */
    private fun modeText(current: Transport?, history: List<HistoryItem>): String {
        if (current == null) return getString(R.string.mode_offline)
        val prefix = getString(R.string.hist_now_using, "").trim()
        val lastSwitch = history.firstOrNull { it.text.startsWith(prefix) }
        val recent = lastSwitch != null && System.currentTimeMillis() - lastSwitch.time < 10 * 60_000
        if (!recent) return getString(R.string.mode_single)
        val ago = DateUtils.getRelativeTimeSpanString(lastSwitch!!.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        return getString(R.string.mode_switched, transportName(current), ago)
    }

    private fun problemRow(f: Finding): View {
        val t = app.controller.state.value.links.firstOrNull { it.id == f.linkId }?.transport
        val a = Plain.advice(f, t?.let(::transportName))
        return row(a.title, a.whatToDo, R.drawable.ic_warn, if (a.action != FixAction.NONE) getString(R.string.fix_open) else null) { runFix(a.action) }
    }

    private fun renderStats() {
        val s = app.stats.state.value
        animateCount(findViewById(R.id.blocked_count), s.blockedToday)
        findViewById<TextView>(R.id.blocked_detail).text =
            getString(R.string.blocked_detail, nf.format(s.checkedToday), nf.format(s.blockedAllTime))
        if (currentTab == 1) renderActivity()
    }

    /** Counts up to the new value (skipped when animations are off, or on the first render). */
    private fun animateCount(tv: TextView, target: Long) {
        val from = shownBlocked
        shownBlocked = target
        if (from < 0 || from == target || !android.animation.ValueAnimator.areAnimatorsEnabled() || target - from > 5_000) {
            tv.text = nf.format(target); return
        }
        android.animation.ValueAnimator.ofFloat(from.toFloat(), target.toFloat()).apply {
            duration = 450
            addUpdateListener { tv.text = nf.format((it.animatedValue as Float).toLong()) }
            start()
        }
    }

    private fun renderSpeedAndUsage() {
        val now = System.currentTimeMillis()
        // Minus what NetPilot forwarded in "use both together": the tunnel counts it a second time.
        val rx = TrafficStats.getTotalRxBytes().let { if (it < 0) it else it - ShieldVpnService.forwardedRx.get() }
        val tx = TrafficStats.getTotalTxBytes().let { if (it < 0) it else it - ShieldVpnService.forwardedTx.get() }
        if (currentTab != 0) { lastRx = rx; lastTx = tx; lastSpeedAt = now; return } // only Home shows these
        if (rx >= 0 && lastRx >= 0 && now > lastSpeedAt) {
            val sec = (now - lastSpeedAt) / 1000.0
            statValue(R.id.stat_down, bitrate((rx - lastRx) * 8 / sec))
            statValue(R.id.stat_up, bitrate((tx - lastTx) * 8 / sec))
        } else if (lastRx < 0) {
            statValue(R.id.stat_down, getString(R.string.speed_unknown))
            statValue(R.id.stat_up, getString(R.string.speed_unknown))
        }
        lastRx = rx; lastTx = tx; lastSpeedAt = now

        val mobile = TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes()
        val total = rx + tx
        val um = findViewById<TextView>(R.id.usage_mobile)
        val uw = findViewById<TextView>(R.id.usage_wifi)
        if (TrafficStats.getMobileRxBytes() >= 0 && rx >= 0) {
            if (now - lastUsageSample > 10_000) {
                lastUsageSample = now
                findViewById<TextView>(R.id.usage_today).text = getString(R.string.usage_today, Formatter.formatShortFileSize(this, app.mobileToday()))
            }
            um.text = getString(R.string.usage_mobile, Formatter.formatShortFileSize(this, mobile))
            uw.text = getString(R.string.usage_wifi, Formatter.formatShortFileSize(this, (total - mobile).coerceAtLeast(0)))
        } else {
            um.setText(R.string.usage_unavailable)
            uw.text = ""
        }

        val ok = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.battery_status).setText(if (ok) R.string.battery_ok else R.string.battery_restricted)
        findViewById<Button>(R.id.battery_fix).visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun bitrate(bps: Double): String = when {
        bps < 0 -> getString(R.string.speed_unknown)
        bps >= 1e6 -> String.format("%.1f Mbps", bps / 1e6)
        bps >= 1e3 -> String.format("%.0f kbps", bps / 1e3)
        else -> String.format("%.0f bps", bps)
    }

    // ---------------------------------------------------------------- Protection

    private fun setupProtection() {
        buildListRows()
        findViewById<Button>(R.id.update_lists).setOnClickListener { updateListsInBackground(force = true) }
        findViewById<Button>(R.id.add_list).setOnClickListener { askListUrl() }
        findViewById<Switch>(R.id.update_on_mobile).apply {
            isChecked = prefs.updateOnMobile
            setOnCheckedChangeListener { _, on -> prefs.updateOnMobile = on }
        }
        findViewById<Button>(R.id.pause_button).setOnClickListener { ShieldVpnService.send(this, ShieldVpnService.ACTION_PAUSE) }
        findViewById<Button>(R.id.allow_site_button).setOnClickListener { askDomain(R.string.dialog_site_allow_title) { allow(it) } }
        findViewById<Button>(R.id.block_site_button).setOnClickListener { askDomain(R.string.dialog_site_block_title) { block(it) } }
        findViewById<Button>(R.id.choose_apps).setOnClickListener { startActivity(Intent(this, AppPickerActivity::class.java)) }
        findViewById<Switch>(R.id.activity_only_blocked).setOnCheckedChangeListener { _, _ -> renderActivity() }
        findViewById<Switch>(R.id.activity_keep).apply {
            isChecked = prefs.keepActivityLog
            setOnCheckedChangeListener { _, on -> prefs.keepActivityLog = on; if (!on) app.stats.clearLog() }
        }
        findViewById<Button>(R.id.clear_activity).setOnClickListener { app.stats.clearLog() }
        renderUserLists()
    }

    private fun listName(src: ListSource): String = src.customName ?: getString(src.title)

    /** Switches on the Protection tab, so a rebuild can drop them from [listSwitches]. */
    private val listRowSwitches = ArrayList<Switch>()

    private fun buildListRows() {
        val lc = findViewById<LinearLayout>(R.id.lists_container)
        lc.removeAllViews()
        listSwitches.values.forEach { it.removeAll(listRowSwitches) }
        listRowSwitches.clear()
        for (src in app.blocklists.sources()) {
            val row = LayoutInflater.from(this).inflate(R.layout.row_item, lc, false) as LinearLayout
            val sw = Switch(this).apply { contentDescription = listName(src) }
            bindListSwitch(src.id, sw)
            listRowSwitches += sw
            row.addView(sw)
            row.findViewById<TextView>(R.id.row_title).text = listName(src)
            row.tag = src.id
            row.setOnClickListener { sw.toggle() }
            if (src.isCustom) row.setOnLongClickListener {
                confirm(getString(R.string.remove_list_title), R.string.list_custom_detail, R.string.remove) {
                    app.blocklists.removeCustom(src.url); buildListRows(); renderLists()
                }
                true
            }
            lc.addView(row)
        }
    }

    private fun askListUrl() {
        val input = EditText(this).apply { hint = "https://"; setSingleLine(); minHeight = dp(48) }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle(R.string.add_list_title)
            .setMessage(getString(R.string.add_list_text, Blocklists.MAX_CUSTOM)).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (!app.blocklists.addCustom(input.text.toString())) { toast(getString(R.string.add_list_bad, Blocklists.MAX_CUSTOM)); return@setPositiveButton }
                buildListRows(); renderLists()
                toast(getString(R.string.add_list_added))
                if (ShieldVpnService.state.value != ShieldState.OFF) updateListsInBackground(force = false)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** One setting, several switches (Home quick toggles and the Protection tab): keep them all in step. */
    private fun bindListSwitch(id: String, sw: Switch) {
        listSwitches.getOrPut(id) { mutableListOf() } += sw
        sw.isChecked = prefs.listEnabled(id)
        sw.setOnCheckedChangeListener { _, on ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            prefs.setListEnabled(id, on)
            syncingSwitches = true
            listSwitches[id]?.forEach { if (it !== sw) it.isChecked = on }
            syncingSwitches = false
            app.blocklists.rebuild()
            if (on && ShieldVpnService.state.value != ShieldState.OFF) updateListsInBackground(force = false)
        }
    }

    private fun updateListsInBackground(force: Boolean) {
        Thread {
            val ok = runCatching { app.blocklists.update(force) }.isSuccess &&
                app.blocklists.status.value.none { it.error != null }
            runOnUiThread { if (force) toast(getString(if (ok) R.string.lists_updated else R.string.lists_update_failed)) }
        }.start()
    }

    private fun renderLists() {
        val lc = findViewById<LinearLayout>(R.id.lists_container)
        val status = app.blocklists.status.value.associateBy { it.id }
        for (i in 0 until lc.childCount) {
            val row = lc.getChildAt(i)
            val src = app.blocklists.sources().firstOrNull { it.id == row.tag } ?: continue
            val st = status[src.id]
            val since = st?.let { DateUtils.getRelativeTimeSpanString(it.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
            val line = when {
                !prefs.listEnabled(src.id) -> getString(R.string.list_status_off)
                st?.error != null && st.domains > 0 -> getString(R.string.list_status_error_prev, since)
                st?.error != null -> getString(if (src.id == "ads") R.string.list_status_error_seed else R.string.list_status_error_none)
                st != null && st.domains > 0 -> getString(R.string.list_status_ready, nf.format(st.domains), since)
                ShieldVpnService.state.value != ShieldState.OFF -> getString(R.string.list_status_soon)
                else -> getString(R.string.list_status_waiting)
            }
            row.findViewById<TextView>(R.id.row_subtitle).apply { visibility = View.VISIBLE; text = getString(src.detail) + "\n" + line }
        }
        val updating = app.blocklists.updating.value
        findViewById<Button>(R.id.update_lists).apply {
            isEnabled = !updating
            setText(if (updating) R.string.updating_lists else R.string.update_lists)
        }
    }

    private fun renderUserLists() {
        val n = prefs.excludedApps.size
        findViewById<TextView>(R.id.apps_summary).text = if (n == 0) getString(R.string.apps_none) else getString(R.string.apps_some, n)
        fill(R.id.allowed_container, prefs.allowedDomains, R.string.allowed_empty) { d -> prefs.allowedDomains = prefs.allowedDomains - d }
        fill(R.id.blocked_container, prefs.blockedDomains, R.string.blocked_sites_empty) { d -> prefs.blockedDomains = prefs.blockedDomains - d }
    }

    private fun fill(containerId: Int, items: Set<String>, empty: Int, remove: (String) -> Unit) {
        val c = findViewById<LinearLayout>(containerId)
        c.removeAllViews()
        if (items.isEmpty()) { c.addView(row(getString(empty), null, null, null)); return }
        for (d in items.sorted()) {
            c.addView(row(d, null, null, getString(R.string.remove), getString(R.string.cd_remove, d)) {
                remove(d); app.blocklists.rebuild(); renderUserLists()
            })
        }
    }

    private fun renderActivity() {
        val s = app.stats.state.value
        val onlyBlocked = findViewById<Switch>(R.id.activity_only_blocked).isChecked
        val c = findViewById<LinearLayout>(R.id.activity_container)
        c.removeAllViews()
        val items = s.recent.filter { !onlyBlocked || it.blocked }.take(40)
        if (items.isEmpty()) c.addView(row(getString(R.string.activity_empty), null, null, null))
        val tf = android.text.format.DateFormat.getTimeFormat(this)
        for (e in items) {
            val sub = if (e.blocked) getString(R.string.activity_blocked_by, reasonName(e)) else getString(R.string.activity_allowed)
            c.addView(row(e.domain, "$sub · ${tf.format(Date(e.time))}", if (e.blocked) R.drawable.ic_block else R.drawable.ic_check, null) {
                if (e.blocked) askAllow(e)
                else confirm(getString(R.string.dialog_block_title, e.domain), R.string.dialog_block_text, R.string.block) { block(e.domain) }
            })
        }
        val tc = findViewById<LinearLayout>(R.id.top_container)
        tc.removeAllViews()
        if (s.topBlocked.isEmpty()) tc.addView(row(getString(R.string.activity_empty), null, null, null))
        for ((d, count) in s.topBlocked.take(5)) {
            tc.addView(row(getString(R.string.top_row, nf.format(count), d), null, null, null) {
                confirm(getString(R.string.dialog_allow_title, d), R.string.dialog_allow_text, R.string.allow) { allow(d) }
            })
        }
    }

    private fun listNameById(id: String?): String = when (id) {
        null -> getString(R.string.activity_blocked_by_you)
        "seed" -> getString(R.string.list_seed)
        else -> app.blocklists.sources().firstOrNull { it.id == id }?.let(::listName) ?: ""
    }

    private fun reasonName(e: LogEntry): String = when (e.reason) {
        BlockReason.USER_BLOCKED -> getString(R.string.activity_blocked_by_you)
        BlockReason.HIDDEN_TRACKER -> getString(R.string.activity_hidden_tracker, listNameById(e.listId))
        else -> listNameById(e.listId)
    }

    /** Allow dialog for a blocked lookup, with a way to tell the list maker it was a mistake. */
    private fun askAllow(e: LogEntry) {
        val src = Blocklists.SOURCES.firstOrNull { it.id == e.listId }
        val text = if (e.reason == BlockReason.HIDDEN_TRACKER) R.string.dialog_allow_hidden_text else R.string.dialog_allow_text
        val b = AlertDialog.Builder(this).setTitle(getString(R.string.dialog_allow_title, e.domain)).setMessage(text)
            .setPositiveButton(R.string.allow) { _, _ -> allow(e.domain) }
            .setNegativeButton(R.string.cancel, null)
        val report = src?.reportUrl
        if (report != null) b.setNeutralButton(R.string.report_block) { _, _ ->
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("domain", e.domain))
            AlertDialog.Builder(this).setTitle(R.string.report_block)
                .setMessage(getString(R.string.report_block_text, e.domain, listName(src)))
                .setPositiveButton(R.string.ok) { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(report))) }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        b.show()
    }

    private fun allow(domain: String) {
        prefs.allowedDomains = prefs.allowedDomains + domain
        prefs.blockedDomains = prefs.blockedDomains - domain
        app.blocklists.rebuild()
        renderUserLists()
    }

    private fun block(domain: String) {
        prefs.blockedDomains = prefs.blockedDomains + domain
        prefs.allowedDomains = prefs.allowedDomains - domain
        app.blocklists.rebuild()
        renderUserLists()
    }

    private fun askDomain(title: Int, then: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.dialog_site_hint); setSingleLine(); minHeight = dp(48) }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val raw = input.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
                val d = DomainSet.normalize(raw)
                if (d == null) toast(getString(R.string.invalid_site)) else then(d)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---------------------------------------------------------------- Connection tab

    private fun setupNetwork() {
        findViewById<Button>(R.id.check_now).setOnClickListener {
            findViewById<TextView>(R.id.check_result).setText(R.string.checking)
            app.controller.diagnoseNow()
        }
        findViewById<Switch>(R.id.alerts_switch).apply {
            isChecked = prefs.connectionAlerts
            setOnCheckedChangeListener { _, on -> prefs.connectionAlerts = on }
        }
        findViewById<Switch>(R.id.keep_cell_switch).apply {
            isChecked = prefs.keepCellularUp
            setOnCheckedChangeListener { _, on -> prefs.keepCellularUp = on; app.controller.setKeepCellularUp(app.keepCellular) }
        }
        findViewById<Button>(R.id.internet_panel).setOnClickListener { open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
        val adv = findViewById<View>(R.id.advanced_container)
        findViewById<Button>(R.id.advanced_toggle).setOnClickListener {
            val show = adv.visibility != View.VISIBLE
            adv.visibility = if (show) View.VISIBLE else View.GONE
            (it as Button).setText(if (show) R.string.advanced_hide else R.string.advanced_show)
        }
        val modes = listOf(Mode.AUTO to R.string.mode_auto, Mode.PREFER_WIFI to R.string.mode_prefer_wifi,
            Mode.PREFER_CELLULAR to R.string.mode_prefer_cell, Mode.WIFI_ONLY to R.string.mode_wifi_only,
            Mode.CELLULAR_ONLY to R.string.mode_cell_only)
        findViewById<Spinner>(R.id.mode_spinner).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { getString(it.second) })
            setSelection(modes.indexOfFirst { it.first == prefs.mode }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val m = modes[pos].first
                    if (m != prefs.mode) { prefs.mode = m; app.controller.setMode(m) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        findViewById<Button>(R.id.fd_start).setOnClickListener {
            val link = findViewById<EditText>(R.id.fd_url).text.toString().trim()
            if (!link.startsWith("https://")) { findViewById<TextView>(R.id.fd_status).setText(R.string.fd_https_only); return@setOnClickListener }
            FastDownloadService.start(this, link)
        }
        findViewById<Button>(R.id.fd_cancel).setOnClickListener { FastDownloadService.cancel(this) }
        findViewById<Button>(R.id.fd_open).setOnClickListener {
            FastDownloadService.state.value?.saved?.let { uri ->
                if (!open(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
                    open(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            }
        }
    }

    private fun renderFastDownload() {
        val d = FastDownloadService.state.value
        val bar = findViewById<android.widget.ProgressBar>(R.id.fd_progress)
        val status = findViewById<TextView>(R.id.fd_status)
        findViewById<Button>(R.id.fd_start).isEnabled = d?.running != true
        findViewById<Button>(R.id.fd_cancel).visibility = if (d?.running == true) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.fd_open).visibility = if (d?.saved != null) View.VISIBLE else View.GONE
        bar.visibility = if (d?.running == true) View.VISIBLE else View.GONE
        if (d == null) { status.text = ""; return }
        val split = splitText(d.perLink)
        status.text = when {
            d.running -> {
                bar.isIndeterminate = d.total <= 0
                if (d.total > 0) bar.progress = (d.done * 1000 / d.total).toInt()
                if (d.total > 0) getString(R.string.fd_progress, Formatter.formatShortFileSize(this, d.done),
                    Formatter.formatShortFileSize(this, d.total), split) else getString(R.string.fd_starting)
            }
            d.error != null -> getString(R.string.fd_failed, d.error)
            else -> {
                val mbps = d.done * 8 / 1e3 / d.millis.coerceAtLeast(1)
                getString(R.string.fd_done, d.name, Formatter.formatShortFileSize(this, d.done),
                    DateUtils.formatElapsedTime(d.millis / 1000), bitrate(mbps * 1e6)) + "\n" + split +
                    (if (!d.split) "\n" + getString(R.string.fd_single) else "")
            }
        }
    }

    /** "Wi-Fi 64% · Mobile data 36%" from bytes per network. */
    private fun splitText(perLink: Map<String, Long>): String {
        val sum = perLink.values.sum().coerceAtLeast(1)
        return perLink.entries.sortedByDescending { it.value }.joinToString(" · ") { (id, b) ->
            val name = getString(when (id) { SpreadDialer.CELL -> R.string.mobile_data; SpreadDialer.WIRED -> R.string.ethernet; else -> R.string.wifi })
            getString(R.string.fd_split, name, (b * 100 / sum).toInt())
        }
    }

    private fun renderNetworkTab() {
        val st = app.controller.state.value
        val nc = findViewById<LinearLayout>(R.id.networks_container)
        nc.removeAllViews()
        // Wi-Fi and mobile data always (even when off), plus any other connection in use, e.g. wired.
        val others = st.links.map { it.transport }.filter { it == Transport.ETHERNET || it == Transport.OTHER }.distinct()
        for ((t, icon) in listOf(Transport.WIFI to R.drawable.ic_wifi, Transport.CELLULAR to R.drawable.ic_cell) + others.map { it to R.drawable.ic_swap }) {
            val l = linkOf(t)
            val detail = l?.let {
                getString(R.string.network_card_detail,
                    it.probe?.httpsMs?.let { ms -> "$ms ms" } ?: getString(R.string.speed_unknown), signalText(it.signalDbm))
            }
            nc.addView(row("${transportName(t)}: ${netStatus(l).first}", detail, icon, null))
        }
        val result = findViewById<TextView>(R.id.check_result)
        val fc = findViewById<LinearLayout>(R.id.findings_container)
        fc.removeAllViews()
        val problems = userProblems()
        if (st.running && st.links.isNotEmpty()) result.text = if (problems.isEmpty()) getString(R.string.check_all_good) else ""
        for (f in problems) fc.addView(problemRow(f))
        findViewById<TextView>(R.id.tech_details).text = technicalDetails()
    }

    private fun signalText(dbm: Int?) = getString(when {
        dbm == null -> R.string.signal_unknown
        dbm > -70 -> R.string.signal_strong
        dbm > -95 -> R.string.signal_ok
        else -> R.string.signal_weak
    })

    private fun transportName(t: Transport) = getString(when (t) {
        Transport.CELLULAR -> R.string.mobile_data
        Transport.ETHERNET -> R.string.ethernet
        Transport.WIFI -> R.string.wifi
        else -> R.string.other_network
    })

    private fun technicalDetails(): String {
        val s = app.controller.state.value
        return buildString {
            appendLine("monitor=${if (s.running) "on" else "off"} mode=${s.mode} shield=${ShieldVpnService.state.value}")
            appendLine("dns=${prefs.dnsUrl} plainFallback=${ShieldVpnService.plainFallbackActive.value}")
            appendLine("filter domains=${app.blocklists.filter.totalDomains}")
            for (l in s.links) {
                val p = l.probe
                appendLine("${l.transport} ${l.id}${if (l.id == s.boundId) " [app-bound]" else ""}${if (l.id == s.systemDefaultId) " [default]" else ""}")
                appendLine("  validated=${l.validated} metered=${l.metered} signal=${l.signalDbm ?: "?"} estDown=${l.estDownKbps ?: "?"}kbps")
                appendLine("  dns=${p?.dnsMs}ms tcp=${p?.tcpMs}ms https=${p?.httpsStatus}/${p?.httpsMs}ms captive=${p?.captiveStatus}")
            }
            appendLine("decision: " + when (val d = s.lastDecision) {
                is Decision.Stay -> "stay (${d.reason})"
                is Decision.Switch -> "switch -> ${d.toId} (${d.reason})"
                is Decision.NoUsableLink -> "none (${d.reason})"
                null -> "-"
            })
            s.findings.forEach { appendLine("[${it.severity}] ${it.code}: ${it.message}") }
            appendLine("events:")
            s.events.take(20).forEach { appendLine("  $it") }
        }
    }

    // ---------------------------------------------------------------- Settings

    private fun setupSettings() {
        val group = findViewById<RadioGroup>(R.id.dns_group)
        val custom = findViewById<EditText>(R.id.custom_dns)
        custom.setText(prefs.customDnsUrl)
        for (p in DnsProvider.entries) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                // Provider name in bold, reason underneath: easier to scan than one paragraph.
                val title = getString(p.title)
                text = android.text.SpannableString(title + "\n" + getString(p.detail)).apply {
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
                }
                minHeight = dp(56)
                tag = p
                setTextColor(getColor(R.color.text_primary))
            })
        }
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as RadioButton
            if (rb.tag == prefs.dnsProvider) group.check(rb.id)
        }
        custom.visibility = if (prefs.dnsProvider == DnsProvider.CUSTOM) View.VISIBLE else View.GONE
        group.setOnCheckedChangeListener { g, checkedId ->
            val p = g.findViewById<View>(checkedId)?.tag as? DnsProvider ?: return@setOnCheckedChangeListener
            prefs.dnsProvider = p
            custom.visibility = if (p == DnsProvider.CUSTOM) View.VISIBLE else View.GONE
        }
        custom.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                val v = custom.text.toString().trim()
                if (v.isEmpty() || v.startsWith("https://")) prefs.customDnsUrl = v else custom.error = getString(R.string.dns_custom_invalid)
            }
        }
        findViewById<Switch>(R.id.plain_fallback).apply {
            isChecked = prefs.allowPlainFallback
            setOnCheckedChangeListener { _, on -> prefs.allowPlainFallback = on }
        }
        findViewById<Switch>(R.id.hardcoded_switch).apply {
            isChecked = prefs.catchHardcodedDns
            setOnCheckedChangeListener { _, on -> prefs.catchHardcodedDns = on; reconnectIfRunning() }
        }
        findViewById<Button>(R.id.always_on_button).setOnClickListener { open(Intent(Settings.ACTION_VPN_SETTINGS)) }
        findViewById<Button>(R.id.battery_button).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.clear_stats).setOnClickListener { app.stats.clear(); toast(getString(R.string.cleared)) }
        findViewById<Button>(R.id.troubleshoot).setOnClickListener { dialog(R.string.troubleshoot, getString(R.string.troubleshoot_text)) }
        findViewById<Button>(R.id.show_intro).setOnClickListener { startActivityForResult(Intent(this, OnboardingActivity::class.java), REQ_ONBOARD) }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        findViewById<TextView>(R.id.about_text).text = getString(R.string.about_text, version, Blocklists.SOURCES.joinToString("; ") { it.credit })
    }

    private fun renderSettings() {
        val on = ShieldVpnService.state.value != ShieldState.OFF
        findViewById<TextView>(R.id.dns_status).apply {
            visibility = if (on) View.VISIBLE else View.GONE
            setText(if (ShieldVpnService.plainFallbackActive.value) R.string.dns_status_plain else R.string.dns_status_ok)
        }
        findViewById<TextView>(R.id.always_on_text).setText(if (ShieldVpnService.alwaysOn.value) R.string.always_on_active else R.string.always_on_text)
    }

    private fun reconnectIfRunning() {
        if (ShieldVpnService.state.value != ShieldState.OFF) ShieldVpnService.send(this, ShieldVpnService.ACTION_RECONNECT)
    }

    // ---------------------------------------------------------------- helpers

    private fun runFix(a: FixAction) {
        when (a) {
            FixAction.INTERNET_PANEL -> open(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            FixAction.WIFI_SETTINGS -> open(Intent(Settings.ACTION_WIFI_SETTINGS))
            FixAction.PRIVATE_DNS_SETTINGS -> open(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            FixAction.BATTERY_SETTINGS -> openBatterySettings()
            FixAction.DATA_SAVER_SETTINGS -> open(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:$packageName")))
            FixAction.DATE_SETTINGS -> open(Intent(Settings.ACTION_DATE_SETTINGS))
            FixAction.NONE -> Unit
        }
    }

    private fun openBatterySettings() {
        if (!open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun open(i: Intent): Boolean = try { startActivity(i); true } catch (e: ActivityNotFoundException) { false }

    /** A list row: title, optional subtitle, icon and action button. With no button, the whole row is the tap target. */
    private fun row(title: String, subtitle: String?, icon: Int?, action: String?, actionDesc: String? = null, onClick: (() -> Unit)? = null): View {
        val v = LayoutInflater.from(this).inflate(R.layout.row_item, null, false)
        v.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        v.findViewById<TextView>(R.id.row_title).text = title
        if (subtitle != null) v.findViewById<TextView>(R.id.row_subtitle).apply { text = subtitle; visibility = View.VISIBLE }
        if (icon != null) v.findViewById<ImageView>(R.id.row_icon).apply {
            setImageResource(icon); setColorFilter(getColor(R.color.primary)); visibility = View.VISIBLE
        }
        val btn = v.findViewById<Button>(R.id.row_action)
        when {
            action != null -> {
                btn.text = action
                btn.visibility = View.VISIBLE
                actionDesc?.let { btn.contentDescription = it }
                btn.setOnClickListener { onClick?.invoke() }
                v.background = null
            }
            onClick != null -> v.setOnClickListener { onClick() }
            else -> v.background = null
        }
        return v
    }

    private fun showCrashReport(report: String) {
        val text = TextView(this).apply {
            this.text = report; setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle(R.string.crash_title).setMessage(R.string.crash_text).setView(scroll)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("NetPilot crash report", report))
                CrashLog.clear(this)
                toast(getString(R.string.crash_copied))
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ -> CrashLog.clear(this) }
            .show()
    }

    private fun dialog(title: Int, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(R.string.ok, null).show()
    }

    private fun confirm(title: String, msg: Int, positive: Int, then: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton(positive) { _, _ -> then() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun toast(msg: String?) { if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Edge-to-edge (enforced when targeting Android 15): keep content clear of the status and navigation bars. */
    private fun applyInsets(root: View) {
        val nav = root.findViewById<View>(R.id.nav)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, 0)
            nav.setPadding(0, dp(6), 0, dp(6) + bottom)
            insets
        }
    }
}
