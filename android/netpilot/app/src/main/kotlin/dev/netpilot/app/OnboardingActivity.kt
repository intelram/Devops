package dev.netpilot.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * Three short pages: what NetPilot does, what it honestly doesn't do, and why Android will
 * ask for the VPN permission. Finishes with RESULT_OK when the user chooses to turn
 * protection on, so the main screen can request the permission straight away.
 */
class OnboardingActivity : Activity() {
    private data class Page(val icon: Int, val title: Int, val text: Int)

    private val pages = listOf(
        Page(R.drawable.ic_shield, R.string.ob1_title, R.string.ob1_text),
        Page(R.drawable.ic_swap, R.string.ob2_title, R.string.ob2_text),
        Page(R.drawable.ic_check, R.string.ob3_title, R.string.ob3_text),
    )
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        index = savedInstanceState?.getInt("page") ?: 0
        val root = findViewById<View>(R.id.root)
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars()); i.top to i.bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(pad, pad + top, pad, pad + bottom)
            insets
        }
        findViewById<Button>(R.id.skip).setOnClickListener { finishWith(false) }
        findViewById<Button>(R.id.ob_next).setOnClickListener {
            if (index < pages.lastIndex) { index++; render() } else finishWith(true)
        }
        findViewById<Button>(R.id.ob_secondary).setOnClickListener {
            if (index == pages.lastIndex) finishWith(false) else if (index > 0) { index--; render() }
        }
        render()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("page", index) }

    @Deprecated("Framework Activity API")
    override fun onBackPressed() {
        if (index > 0) { index--; render() } else finishWith(false)
    }

    private fun render() {
        val p = pages[index]
        findViewById<ImageView>(R.id.ob_icon).apply { setImageResource(p.icon); setColorFilter(getColor(R.color.primary)) }
        findViewById<TextView>(R.id.ob_title).setText(p.title)
        findViewById<TextView>(R.id.ob_text).setText(p.text)
        findViewById<TextView>(R.id.ob_dots).apply {
            text = pages.indices.joinToString("  ") { if (it == index) "●" else "○" }
            contentDescription = getString(R.string.cd_page, index + 1, pages.size)
        }
        val last = index == pages.lastIndex
        findViewById<Button>(R.id.ob_next).setText(if (last) R.string.get_started else R.string.next)
        findViewById<Button>(R.id.ob_secondary).apply {
            setText(if (last) R.string.not_now else R.string.back)
            visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        }
        findViewById<Button>(R.id.skip).visibility = if (last) View.INVISIBLE else View.VISIBLE
    }

    private fun finishWith(connect: Boolean) {
        (application as NetPilotApp).prefs.onboarded = true
        setResult(if (connect) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
