package dev.netpilot.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.netpilot.core.Decision
import dev.netpilot.core.Mode
import dev.netpilot.core.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

/** Plain framework Activity (no AndroidX), so the app builds with only Maven Central dependencies. */
class MainActivity : Activity() {
    private lateinit var b: Views
    private val controller get() = (application as NetPilotApp).controller
    private val uiScope = MainScope()
    private var stateJob: Job? = null

    private class Views(a: Activity) {
        val mode: Spinner = a.findViewById(R.id.mode)
        val keepCell: Switch = a.findViewById(R.id.keepCell)
        val start: Button = a.findViewById(R.id.start)
        val stop: Button = a.findViewById(R.id.stop)
        val diagnose: Button = a.findViewById(R.id.diagnose)
        val panel: Button = a.findViewById(R.id.panel)
        val status: TextView = a.findViewById(R.id.status)
        val url: EditText = a.findViewById(R.id.url)
        val download: Button = a.findViewById(R.id.download)
        val dlStatus: TextView = a.findViewById(R.id.dlStatus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        b = Views(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        b.mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Mode.entries.map { it.name })
        // Show the controller's current mode, so re-creating the screen (e.g. rotation) doesn't reset it.
        b.mode.setSelection(controller.state.value.mode.ordinal)
        b.mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val picked = Mode.entries[pos]
                if (picked != controller.state.value.mode) controller.setMode(picked)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        b.keepCell.setOnCheckedChangeListener { _, on -> controller.setKeepCellularUp(on) }
        b.start.setOnClickListener { NetPilotService.start(this, Mode.entries[b.mode.selectedItemPosition], b.keepCell.isChecked) }
        b.stop.setOnClickListener { NetPilotService.stop(this) }
        b.diagnose.setOnClickListener { controller.diagnoseNow() }
        b.panel.setOnClickListener { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
        b.download.setOnClickListener { runSplitDownload() }
    }

    override fun onStart() {
        super.onStart()
        stateJob = uiScope.launch {
            controller.state.collect { s ->
                b.status.text = buildString {
                    appendLine(if (s.running) "RUNNING · mode ${s.mode}" else "STOPPED")
                    appendLine("Scope: this app's traffic only. Failover, NOT bandwidth bonding.")
                    appendLine()
                    for (l in s.links) {
                        val p = l.probe
                        val tag = buildList {
                            if (l.id == s.boundId) add("APP-BOUND")
                            if (l.id == s.systemDefaultId) add("SYS-DEFAULT")
                        }.joinToString(",")
                        appendLine("${l.transport} ${l.id} [$tag]")
                        appendLine("  validated=${l.validated} metered=${l.metered} signal=${l.signalDbm ?: "?"}dBm estDown=${l.estDownKbps ?: "?"}kbps")
                        appendLine("  probe: dns=${p?.dnsMs}ms tcp=${p?.tcpMs}ms https=${p?.httpsStatus}/${p?.httpsMs}ms captive=${p?.captiveStatus}")
                    }
                    appendLine()
                    appendLine("Decision: " + when (val d = s.lastDecision) {
                        is Decision.Stay -> "stay (${d.reason})"
                        is Decision.Switch -> "switch -> ${d.toId} (${d.reason})"
                        is Decision.NoUsableLink -> "no usable link (${d.reason})"
                        null -> "-"
                    })
                    appendLine()
                    appendLine("Diagnosis:")
                    if (s.findings.isEmpty()) appendLine("  no issues")
                    s.findings.forEach { appendLine("  [${it.severity}] ${it.message}\n     → ${it.remediation}") }
                    appendLine()
                    appendLine("Events:")
                    s.events.take(30).forEach { appendLine("  $it") }
                }
            }
        }
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun runSplitDownload() {
        val url = runCatching { URL(b.url.text.toString().trim()) }.getOrNull() ?: run { b.dlStatus.text = "invalid URL"; return }
        val nets = controller.networks().filterKeys { id ->
            controller.state.value.links.any { it.id == id && it.transport in setOf(Transport.WIFI, Transport.CELLULAR) && it.validated }
        }
        if (nets.isEmpty()) { b.dlStatus.text = "Start monitoring first; need at least one validated Wi-Fi/cellular network."; return }
        b.dlStatus.text = "Downloading over ${nets.size} network(s)…"
        uiScope.launch {
            val out = File(cacheDir, "split-test.bin")
            val r = runCatching {
                MultipathDownloader().download(url, nets, out) { done, total ->
                    runOnUiThread { b.dlStatus.text = "%.1f / %.1f MB".format(done / 1e6, total / 1e6) }
                }
            }
            b.dlStatus.text = r.fold(
                onSuccess = { res ->
                    val mbps = res.bytes * 8 / 1e3 / res.millis.coerceAtLeast(1)
                    "Done: %.1f MB in %d ms (%.1f Mbit/s)\nper network: %s\nsha256 %s".format(
                        res.bytes / 1e6, res.millis, mbps,
                        res.bytesPerLink.entries.joinToString { "${it.key}=%.1fMB".format(it.value / 1e6) }, res.sha256)
                },
                onFailure = { "Failed: ${it.message}" },
            )
            out.delete()
        }
    }
}
