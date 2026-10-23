package dev.netpilot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.netpilot.core.Mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process, and so the failover loop, alive while the app is in the background.
 * Uses the specialUse FGS type (Android 14+). See docs/DESIGN.md §6 for the Play policy impact.
 */
class NetPilotService : Service() {
    private val scope = MainScope()
    private var notifyJob: Job? = null
    private val controller get() = (application as NetPilotApp).controller

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        // The specialUse type exists from Android 14; earlier versions take no type for this service.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification("Starting…"))
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let(Mode::valueOf) ?: Mode.PREFER_WIFI
        controller.start(mode, intent?.getBooleanExtra(EXTRA_KEEP_CELL, true) ?: true)
        notifyJob?.cancel() // each Start tap re-enters here; keep a single updater
        notifyJob = scope.launch {
            controller.state.collect { s ->
                val bound = s.links.firstOrNull { it.id == s.boundId }?.transport?.name ?: "system default"
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, notification("App traffic: $bound · ${s.links.size} networks · mode ${s.mode}"))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Network failover", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NetPilot failover (not bonding)")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "failover"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.netpilot.STOP"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_KEEP_CELL = "keepCell"

        fun start(ctx: Context, mode: Mode, keepCellularUp: Boolean) = ctx.startForegroundService(
            Intent(ctx, NetPilotService::class.java).putExtra(EXTRA_MODE, mode.name).putExtra(EXTRA_KEEP_CELL, keepCellularUp))

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, NetPilotService::class.java).setAction(ACTION_STOP))
        }
    }
}
