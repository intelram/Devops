package dev.netpilot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.netpilot.app.databinding.ActivityMainBinding
import dev.netpilot.core.Decision
import dev.netpilot.core.Mode
import dev.netpilot.core.Transport
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

class MainActivity : ComponentActivity() {
    private lateinit var b: ActivityMainBinding
    private val controller get() = (application as NetPilotApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        b.mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Mode.entries.map { it.name })
        b.mode.setSelection(Mode.PREFER_WIFI.ordinal)
        b.mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = controller.setMode(Mode.entries[pos])
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        b.keepCell.setOnCheckedChangeListener { _, on -> controller.setKeepCellularUp(on) }
        b.start.setOnClickListener { NetPilotService.start(this, Mode.entries[b.mode.selectedItemPosition], b.keepCell.isChecked) }
        b.stop.setOnClickListener { NetPilotService.stop(this) }
        b.diagnose.setOnClickListener { controller.diagnoseNow() }
        b.panel.setOnClickListener { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
        b.download.setOnClickListener { runSplitDownload() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
    }

    private fun runSplitDownload() {
        val url = runCatching { URL(b.url.text.toString().trim()) }.getOrNull() ?: run { b.dlStatus.text = "invalid URL"; return }
        val nets = controller.networks().filterKeys { id ->
            controller.state.value.links.any { it.id == id && it.transport in setOf(Transport.WIFI, Transport.CELLULAR) && it.validated }
        }
        if (nets.isEmpty()) { b.dlStatus.text = "Start monitoring first; need at least one validated Wi-Fi/cellular network."; return }
        b.dlStatus.text = "Downloading over ${nets.size} network(s)…"
        lifecycleScope.launch {
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
