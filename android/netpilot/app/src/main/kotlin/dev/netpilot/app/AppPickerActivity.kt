package dev.netpilot.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Lets the user pick apps that bypass the VPN (Android's per-app VPN "disallowed" list).
 * Lists launchable apps only, which the manifest <queries> entry makes visible without
 * the broad QUERY_ALL_PACKAGES permission.
 */
class AppPickerActivity : Activity() {
    private class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private var all = emptyList<AppItem>()
    private var shown = emptyList<AppItem>()
    private val selected = HashSet<String>()
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val root = findViewById<View>(R.id.root)
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()); i.top to i.bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(pad, pad + top, pad, pad + bottom)
            insets
        }
        val prefs = (application as NetPilotApp).prefs
        selected += prefs.excludedApps

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.row_app, parent, false)
                val item = shown[position]
                v.findViewById<ImageView>(R.id.app_icon).setImageDrawable(item.icon)
                v.findViewById<TextView>(R.id.app_name).text = item.label
                val checked = item.pkg in selected
                v.findViewById<CheckBox>(R.id.app_check).isChecked = checked
                v.contentDescription = item.label
                // stateDescription exists from Android 11; on Android 10 the checkbox state is read instead.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    v.stateDescription = getString(if (checked) R.string.app_skips else R.string.app_protected)
                }
                return v
            }
        }
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.choiceMode = ListView.CHOICE_MODE_NONE
        list.setOnItemClickListener { _, _, pos, _ ->
            val pkg = shown[pos].pkg
            if (!selected.remove(pkg)) selected += pkg
            adapter.notifyDataSetChanged()
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        findViewById<Button>(R.id.save).setOnClickListener {
            prefs.excludedApps = selected.toSet()
            if (ShieldVpnService.state.value != ShieldState.OFF) ShieldVpnService.send(this, ShieldVpnService.ACTION_RECONNECT)
            finish()
        }
        loadApps()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
                .map { AppItem(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedWith(compareByDescending<AppItem> { it.pkg in selected }.thenBy { it.label.lowercase() })
            runOnUiThread {
                all = items
                filter(findViewById<EditText>(R.id.search).text.toString())
                findViewById<View>(R.id.empty).visibility = View.GONE
            }
        }.start()
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) all else all.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }
}
