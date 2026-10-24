package dev.netpilot.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Launches the real screens on the Android framework (Robolectric) for every supported
 * Android version, and draws them: the 0.2.0 launch crash was in ConnectButton.onDraw,
 * which only runs when a frame is actually drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30, 33, 34, 35])
class LaunchTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun draw(a: Activity) {
        val v = a.window.decorView
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2200)
        v.draw(Canvas(Bitmap.createBitmap(1080, 2200, Bitmap.Config.ARGB_8888)))
    }

    @Test fun firstLaunchShowsOnboarding() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(); draw(c.get())
        val ob = Robolectric.buildActivity(OnboardingActivity::class.java).setup()
        idle(); draw(ob.get())
        c.pause().stop().destroy()
    }

    @Test fun everyTabDrawsInEveryState() {
        (RuntimeEnvironment.getApplication() as NetPilotApp).prefs.onboarded = true
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        idle()
        val button = a.findViewById<ConnectButton>(R.id.connect_button)
        for (state in ShieldState.values()) {
            button.state = state
            for (tab in listOf(R.id.nav_protection, R.id.nav_network, R.id.nav_settings, R.id.nav_home)) {
                a.findViewById<View>(tab).performClick(); idle(); draw(a)
            }
        }
        c.configurationChange(android.content.res.Configuration(a.resources.configuration).apply { orientation = 2 })
        idle(); draw(c.get())
        c.pause().stop().destroy()
    }

    @Test fun appPickerDraws() {
        val c = Robolectric.buildActivity(AppPickerActivity::class.java).setup()
        idle(); draw(c.get())
    }

    @Test fun crashReportShownOnNextLaunch() {
        val app = RuntimeEnvironment.getApplication() as NetPilotApp
        app.prefs.onboarded = true
        java.io.File(app.filesDir, "last_crash.txt").writeText("boom")
        Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(d); assertTrue(d.isShowing)
    }

    @Test fun sharedLinkGoesToFastDownloadWithoutStarting() {
        (RuntimeEnvironment.getApplication() as NetPilotApp).prefs.onboarded = true
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "Look at this: https://example.org/files/big.zip thanks")
        val c = Robolectric.buildActivity(MainActivity::class.java, share).setup()
        idle(); draw(c.get())
        val a = c.get()
        org.junit.Assert.assertEquals("https://example.org/files/big.zip", a.findViewById<android.widget.EditText>(R.id.fd_url).text.toString())
        assertTrue(a.findViewById<View>(R.id.tab_network).visibility == View.VISIBLE)
        org.junit.Assert.assertNull(FastDownloadService.state.value) // nothing starts without a tap
    }

    @Test fun usingBothAsksFirst() {
        val app = RuntimeEnvironment.getApplication() as NetPilotApp
        app.prefs.onboarded = true
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val sw = c.get().findViewById<android.widget.Switch>(R.id.both_switch)
        sw.performClick(); idle()
        assertTrue(!sw.isChecked && !app.prefs.spreadMode) // not yet: the explanation comes first
        val d = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(d)
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(sw.isChecked && app.prefs.spreadMode)
        draw(c.get())
    }

    @Test fun fileNamesAreSafe() {
        org.junit.Assert.assertEquals("report 2024.pdf", FastDownloadService.fileNameFor(java.net.URL("https://x.org/a/report%202024.pdf")))
        org.junit.Assert.assertEquals("_.._etc_passwd", FastDownloadService.fileNameFor(java.net.URL("https://x.org/%2F..%2Fetc%2Fpasswd")))
        org.junit.Assert.assertEquals("download", FastDownloadService.fileNameFor(java.net.URL("https://x.org/")))
    }
}
