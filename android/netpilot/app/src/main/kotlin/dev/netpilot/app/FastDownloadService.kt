package dev.netpilot.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder

/** State of the current or last fast download, for the screen. */
data class FastDownload(
    val url: String,
    val name: String,
    val done: Long = 0,
    val total: Long = 0,
    val perLink: Map<String, Long> = emptyMap(),
    val running: Boolean = true,
    val error: String? = null,
    val saved: Uri? = null,
    val millis: Long = 0,
    /** False when the server didn't allow split downloads and only one network was used. */
    val split: Boolean = true,
)

/**
 * Downloads one file over Wi-Fi and mobile data at the same time (different parts on each
 * network), then saves it to Downloads/NetPilot. Runs as a foreground service so it continues
 * while you use other apps. One download at a time.
 */
class FastDownloadService : Service() {

    companion object {
        private const val TAG = "FastDownload"
        private const val EXTRA_URL = "url"
        private const val ACTION_CANCEL = "dev.netpilot.CANCEL_DOWNLOAD"
        private const val NOTIF_ID = 20

        private val _state = MutableStateFlow<FastDownload?>(null)
        val state: StateFlow<FastDownload?> = _state

        fun start(ctx: Context, url: String) {
            val i = Intent(ctx, FastDownloadService::class.java).putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, FastDownloadService::class.java).setAction(ACTION_CANCEL))
        }

        /** A safe file name from the link: last path part, decoded, with anything unusual replaced. */
        fun fileNameFor(url: URL): String {
            val raw = runCatching { URLDecoder.decode(url.path.substringAfterLast('/'), "UTF-8") }.getOrDefault("")
            val clean = raw.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim('.', ' ').take(100)
            return clean.ifEmpty { "download" }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            _state.value = _state.value?.copy(running = false, error = getString(R.string.fd_cancelled))
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val link = intent?.getStringExtra(EXTRA_URL)
        val url = link?.let { runCatching { URL(it.trim()) }.getOrNull() }
        startForegroundCompat(notification(getString(R.string.fd_starting), 0, 0))
        if (url == null || url.protocol != "https") {
            _state.value = FastDownload(link ?: "", "", running = false, error = getString(R.string.fd_https_only))
            stopSelfSafely(); return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY // one at a time; the screen disables the button
        val name = fileNameFor(url)
        _state.value = FastDownload(url.toString(), name)
        job = scope.launch { run(url, name) }
        return START_NOT_STICKY
    }

    private suspend fun run(url: URL, name: String) {
        val nets = networks()
        val tmp = File(cacheDir, "fast-download.part")
        tmp.delete()
        val progress = { done: Long, total: Long, perLink: Map<String, Long> ->
            _state.value = _state.value?.copy(done = done, total = total, perLink = perLink)
            val now = System.currentTimeMillis()
            if (now - lastNotify > 1_000) { lastNotify = now; notifyProgress(name, done, total) }
        }
        try {
            if (nets.isEmpty()) throw IOException(getString(R.string.fd_no_network))
            val d = MultipathDownloader(workersPerLink = 3)
            val result = try {
                d.download(url, nets, tmp, onProgress = progress)
            } catch (e: MultipathDownloader.NotRangeCapable) {
                // The server sends files only in one piece: fall back to an ordinary download.
                Log.i(TAG, "no range support: ${e.message}")
                _state.value = _state.value?.copy(split = false)
                val (id, net) = nets.entries.first()
                d.downloadSingle(url, id, net, tmp, progress)
            }
            val uri = save(tmp, name)
            _state.value = _state.value?.copy(done = result.bytes, total = result.bytes, perLink = result.bytesPerLink,
                running = false, saved = uri, millis = result.millis)
            notifyDone(name, uri)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            _state.value = _state.value?.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            notifyFailed(name)
        } finally {
            tmp.delete()
            stopSelfSafely()
        }
    }

    /** Wi-Fi first, then mobile data, then wired: the same networks "use both together" would use. */
    private fun networks(): Map<String, Network> {
        val d = SpreadDialer(getSystemService(ConnectivityManager::class.java)) { null }
        d.refresh()
        val order = listOf(SpreadDialer.WIFI, SpreadDialer.CELL, SpreadDialer.WIRED)
        return d.links.sortedBy { order.indexOf(it.id) }.distinctBy { it.id }.associate { it.id to it.network }
    }

    /** Copies the finished file into Downloads/NetPilot (no storage permission needed on Android 10+). */
    private fun save(src: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NetPilot")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("can't create the file in Downloads")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IOException("can't write to Downloads")
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused", e) // e.g. the daily limit for this service type on Android 15
        }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun builder(): Notification.Builder {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_NETWORK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ShieldVpnService.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_swap)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
    }

    private fun notification(text: String, done: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(this, 4, Intent(this, FastDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return builder().setContentTitle(getString(R.string.fd_title)).setContentText(text).setOngoing(true)
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun notifyProgress(name: String, done: Long, total: Long) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(name, done, total))

    private fun notifyDone(name: String, uri: Uri) {
        val view = PendingIntent.getActivity(this, 5, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, builder()
            .setContentTitle(getString(R.string.fd_done_title)).setContentText(name).setContentIntent(view).setAutoCancel(true).build())
    }

    private fun notifyFailed(name: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, builder()
            .setContentTitle(getString(R.string.fd_failed_title)).setContentText(name).setAutoCancel(true).build())
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
