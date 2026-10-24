package dev.netpilot.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.SystemClock
import android.provider.Settings
import dev.netpilot.core.UsageMeter
import kotlinx.coroutines.MainScope

/** Process-wide objects shared by the screens, the VPN service and the Quick Settings tile. */
class NetPilotApp : Application() {
    val prefs by lazy { Prefs(this) }
    val stats by lazy { ShieldStats(prefs) }
    val blocklists by lazy { Blocklists(this, prefs) }
    val controller by lazy { FailoverController(this, MainScope()) }

    /** Number of visible screens; monitoring runs while a screen is visible or protection is on. */
    private var visibleScreens = 0

    private var lastWifiAlert = 0L

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this) // first, so any later startup failure is captured
        ShieldVpnService.ensureChannels(this)
        controller.onWifiBroken = ::alertWifiBroken
        controller.restoreHistory(prefs.loadHistory())
        controller.onHistoryChanged = prefs::saveHistory
    }

    /** "Wi-Fi isn't working" notification, at most every 10 minutes, only if the user wants alerts. */
    private fun alertWifiBroken() {
        if (!prefs.connectionAlerts) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        if (now - lastWifiAlert < 10 * 60_000) return
        lastWifiAlert = now
        val panel = PendingIntent.getActivity(this, 2,
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val text = getString(R.string.alert_wifi_text, getString(R.string.hist_no_internet, getString(R.string.wifi)) + ".")
        getSystemService(NotificationManager::class.java).notify(12, Notification.Builder(this, ShieldVpnService.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_warn).setContentTitle(getString(R.string.alert_wifi_title))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(panel).setAutoCancel(true).build())
    }

    /**
     * Updates and returns mobile data used today (bytes, approximate). Called by the screen
     * and by the protection service, so the figure keeps up while NetPilot is running.
     */
    @Synchronized
    fun mobileToday(): Long {
        val counter = TrafficStats.getMobileRxBytes().let { rx -> if (rx < 0) -1 else rx + TrafficStats.getMobileTxBytes() }
        val bootId = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000 // boot time, minute precision
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val next = UsageMeter.update(prefs.loadUsage(), day, bootId, counter)
        prefs.saveUsage(next)
        return next.today
    }

    fun startMonitoring() = controller.start(prefs.mode, prefs.keepCellularUp)

    fun stopMonitoringIfIdle() {
        if (visibleScreens == 0 && ShieldVpnService.state.value == ShieldState.OFF) controller.stop()
    }

    fun screenVisible(visible: Boolean) {
        visibleScreens = (visibleScreens + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible) startMonitoring() else stopMonitoringIfIdle()
    }
}
