package dev.netpilot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import dev.netpilot.core.Dns
import dev.netpilot.core.DnsCache
import dev.netpilot.core.DnsQuery
import dev.netpilot.core.DomainFilter
import dev.netpilot.core.Verdict
import dev.netpilot.core.Packets
import dev.netpilot.core.UdpPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.NumberFormat
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

enum class ShieldState { OFF, CONNECTING, ON, PAUSED }

/**
 * On-device protection VPN. Only DNS goes through the tunnel: the VPN's address space is a
 * private /32 holding the DNS server it advertises, so web, video and app traffic keep using
 * Wi-Fi or mobile data directly at full speed. Each lookup is checked against the blocklists
 * and either answered with 0.0.0.0 / :: (blocked) or forwarded over encrypted DNS.
 *
 * No server, no tunnel to a remote endpoint, no TLS interception.
 */
class ShieldVpnService : VpnService() {

    companion object {
        private const val TAG = "ShieldVpn"
        const val ACTION_CONNECT = "dev.netpilot.CONNECT"
        const val ACTION_DISCONNECT = "dev.netpilot.DISCONNECT"
        const val ACTION_PAUSE = "dev.netpilot.PAUSE"
        const val ACTION_RECONNECT = "dev.netpilot.RECONNECT"
        private const val NOTIF_ID = 10
        private const val CH_PROTECTION = "protection"
        const val CH_ALERTS = "alerts"

        private const val TUN4 = "10.111.222.2"
        private const val DNS4 = "10.111.222.1"
        private const val TUN6 = "fd00:6e70:696c::2"
        private const val DNS6 = "fd00:6e70:696c::1"
        /** Public resolvers some apps use directly, bypassing the phone's DNS (opt-in). */
        private val PUBLIC_DNS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9",
            "149.112.112.112", "208.67.222.222", "208.67.220.220")
        const val PAUSE_MINUTES = 5
        private const val MTU = 8_192
        private const val FIREFOX_CANARY = "use-application-dns.net"
        /** How often the running service checks whether a list is due (lists update daily). */
        private const val UPDATE_CHECK_SECONDS = 3_600

        private val _state = MutableStateFlow(ShieldState.OFF)
        val state: StateFlow<ShieldState> = _state
        private val _pausedUntil = MutableStateFlow(0L)
        val pausedUntil: StateFlow<Long> = _pausedUntil
        private val _plainFallbackActive = MutableStateFlow(false)
        /** True while encrypted DNS is failing and lookups use the network's plain DNS. */
        val plainFallbackActive: StateFlow<Boolean> = _plainFallbackActive
        private val _alwaysOn = MutableStateFlow(false)
        /** True when Android's Always-on VPN setting controls this service. */
        val alwaysOn: StateFlow<Boolean> = _alwaysOn

        /**
         * Plain startService: callers are the visible app or the running service itself. The
         * service promotes itself to the foreground only once the tunnel exists, because the
         * systemExempted type is refused before that, and a startForegroundService() that is
         * never promoted would crash the app.
         */
        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(action))
        }

            /** Ends a pause early; the service notices within a second and updates its notification. */
        fun resumeNow() { _pausedUntil.value = 0 }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROTECTION,
                ctx.getString(R.string.channel_protection), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS,
                ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private val app get() = application as NetPilotApp
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var out: FileOutputStream? = null
    private val cache = DnsCache()
    private lateinit var upstream: Upstream
    private lateinit var pool: ThreadPoolExecutor
    @Volatile private var running = false
    private var tick: Thread? = null

    override fun onCreate() {
        super.onCreate()
        upstream = Upstream(getSystemService(ConnectivityManager::class.java), app.prefs)
        pool = ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue(512)).apply { allowCoreThreadTimeOut(true) }
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { app.prefs.wantProtection = false; shutdown(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pause(); return START_STICKY }
            ACTION_RECONNECT -> { if (running) establish(reconnect = true); return START_STICKY }
            // ACTION_CONNECT, the system's always-on start ("android.net.VpnService"), or a sticky restart.
            else -> connect()
        }
        return START_STICKY
    }

    private fun connect() {
        if (running) return
        _state.value = ShieldState.CONNECTING
        app.prefs.wantProtection = true
        app.blocklists.load()
        if (!establish(reconnect = false)) { shutdown(); return }
        startForegroundCompat(notification())
        _alwaysOn.value = isAlwaysOn
        running = true
        _state.value = ShieldState.ON
        app.startMonitoring()
        reader = Thread(::readLoop, "shield-reader").also { it.start() }
        tick = Thread(::tickLoop, "shield-tick").also { it.start() }
        updateListsIfDue()
        requestTileRefresh()
    }

    /** Background list refresh; [Blocklists.update] applies the mobile-data policy itself. */
    private fun updateListsIfDue() {
        if (!app.blocklists.needsUpdate()) return
        try { pool.execute { runCatching { app.blocklists.update(force = false) } } } catch (_: RejectedExecutionException) { }
    }

    /** Builds (or rebuilds, for changed exclusions) the TUN interface. */
    private fun establish(reconnect: Boolean): Boolean {
        val prefs = app.prefs
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU) // only DNS crosses this interface; a large MTU avoids truncating big answers
            .setBlocking(true)
            .setMetered(false) // inherit meteredness from Wi-Fi / mobile data instead of always "metered"
            .addAddress(TUN4, 32).addDnsServer(DNS4).addRoute(DNS4, 32)
            .addAddress(TUN6, 128).addDnsServer(DNS6).addRoute(DNS6, 128)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (prefs.catchHardcodedDns) PUBLIC_DNS.forEach { b.addRoute(it, 32) }
        // NetPilot's own sockets (encrypted DNS, list downloads, probes) must not loop through itself.
        b.addDisallowedApplication(packageName)
        for (pkg in prefs.excludedApps) {
            try { b.addDisallowedApplication(pkg) } catch (_: PackageManager.NameNotFoundException) { /* uninstalled */ }
        }
        val fd = try { b.establish() } catch (e: Exception) { Log.e(TAG, "establish failed", e); null }
            ?: return false // not prepared / permission revoked
        val old = tun
        tun = fd
        synchronized(this) { out = FileOutputStream(fd.fileDescriptor) }
        if (reconnect) {
            // Old reader exits when its fd closes; start one on the new interface.
            runCatching { old?.close() }
            reader = Thread(::readLoop, "shield-reader").also { it.start() }
        }
        cache.clear()
        return true
    }

    private fun readLoop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(MTU + 64)
        try {
            while (running && tun === fd) {
                val n = input.read(buf)
                if (n <= 0) continue
                val pkt = Packets.parseUdp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                handle(pkt)
            }
        } catch (e: IOException) {
            if (running && tun === fd) Log.w(TAG, "tun read failed", e)
        }
    }

    private fun handle(pkt: UdpPacket) {
        val q = Dns.parseQuery(pkt.payload) ?: return
        val now = System.currentTimeMillis()
        val paused = now < _pausedUntil.value
        // Firefox's canary: "no such domain" tells it to use the phone's DNS (this filter)
        // instead of switching on its own encrypted DNS and skipping protection.
        if (!paused && q.name == FIREFOX_CANARY) { write(pkt, Dns.errorResponse(q, Dns.RCODE_NXDOMAIN)); return }
        val filter = app.blocklists.filter
        val verdict = if (paused) null else filter.check(q.name)
        if (verdict?.blocked == true) {
            app.stats.record(q.name, true, verdict.reason, verdict.listId)
            write(pkt, Dns.blockedResponse(q)); return
        }
        cache.get(q.name, q.type, q.id)?.let { answer(pkt, q, verdict, filter, it); return }
        try {
            pool.execute {
                val reply = try {
                    val a = upstream.resolve(pkt.payload, q.id)
                    _plainFallbackActive.value = a.via == Upstream.Via.PLAIN
                    cache.put(q.name, q.type, a.response)
                    a.response
                } catch (e: IOException) {
                    null
                }
                if (reply == null) { app.stats.record(q.name, false, null, null); write(pkt, Dns.errorResponse(q)) }
                else answer(pkt, q, verdict, filter, reply)
            }
        } catch (e: RejectedExecutionException) {
            app.stats.record(q.name, false, null, null)
            write(pkt, Dns.errorResponse(q)) // overloaded: fail fast rather than queue forever
        }
    }

    /**
     * Sends an upstream (or cached) answer, unless it is an alias to a blocked domain: a tracker
     * hidden behind a first-party name (CNAME cloaking). Checked on every answer, so a list
     * update applies to cached answers too.
     */
    private fun answer(pkt: UdpPacket, q: DnsQuery, verdict: Verdict?, filter: DomainFilter, reply: ByteArray) {
        val hidden = verdict?.let { filter.checkAliases(it, Dns.cnameTargets(reply)) }
        app.stats.record(q.name, hidden != null, hidden?.reason, hidden?.listId)
        write(pkt, if (hidden != null) Dns.blockedResponse(q) else reply)
    }

    private fun write(req: UdpPacket, payload: ByteArray) {
        val header = if (req.ipVersion == 4) 28 else 48
        // Too big for one datagram: answer "truncated" so the resolver knows rather than timing out.
        val body = if (payload.size + header > MTU) Dns.truncated(payload) else payload
        val p = Packets.reply(req, body)
        synchronized(this) {
            try { out?.write(p) } catch (e: IOException) { Log.w(TAG, "tun write failed", e) }
        }
    }

    private fun tickLoop() {
        var n = 0
        while (running) {
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
            app.stats.flush(persist = ++n % 30 == 0)
            if (n % 30 == 0) app.mobileToday()
            if (n % UPDATE_CHECK_SECONDS == 0) updateListsIfDue()
            val wasPaused = _state.value == ShieldState.PAUSED
            val paused = System.currentTimeMillis() < _pausedUntil.value
            if (paused != wasPaused) { _state.value = if (paused) ShieldState.PAUSED else ShieldState.ON; requestTileRefresh() }
            if (n % 5 == 0 || paused != wasPaused) notifyStatus()
        }
    }

    private fun pause() {
        if (!running) return
        _pausedUntil.value = System.currentTimeMillis() + PAUSE_MINUTES * 60_000L
        _state.value = ShieldState.PAUSED
        cache.clear()
        notifyStatus()
        requestTileRefresh()
    }

    private fun shutdown() {
        running = false
        _pausedUntil.value = 0
        _plainFallbackActive.value = false
        _alwaysOn.value = false
        app.stats.flush(persist = true)
        runCatching { tun?.close() }
        tun = null
        synchronized(this) { out = null }
        reader?.interrupt(); tick?.interrupt()
        _state.value = ShieldState.OFF
        app.stopMonitoringIfIdle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        requestTileRefresh()
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user turned us off in system settings.
        val other = running
        shutdown()
        if (other) postAlert(getString(R.string.alert_revoked_title), getString(R.string.alert_revoked_text))
    }

    override fun onDestroy() {
        if (running) shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // If the platform refuses, the VPN keeps running: the system binds VPN services itself.
            Log.w(TAG, "startForeground refused", e)
        }
    }

    private fun notifyStatus() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val s = app.stats.state.value
        val nf = NumberFormat.getIntegerInstance()
        val paused = System.currentTimeMillis() < _pausedUntil.value
        val title = getString(if (paused) R.string.notif_paused_title else R.string.notif_on_title)
        val text = if (paused) getString(R.string.notif_paused_text, PAUSE_MINUTES)
        else getString(R.string.notif_on_text, nf.format(s.blockedToday))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, label: Int, req: Int) = Notification.Action.Builder(null, getString(label),
            PendingIntent.getService(this, req, Intent(this, ShieldVpnService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)).build()
        return Notification.Builder(this, CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (!paused) addAction(action(ACTION_PAUSE, R.string.action_pause, 1)) }
            .addAction(action(ACTION_DISCONNECT, R.string.action_disconnect, 2))
            .build()
    }

    private fun postAlert(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(11, Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_shield).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }

    private fun requestTileRefresh() {
        runCatching { TileService.requestListeningState(this, android.content.ComponentName(this, ShieldTileService::class.java)) }
    }
}
