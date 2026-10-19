package dev.netpilot.app

import android.app.Application
import kotlinx.coroutines.MainScope

/** Holds the single controller so the Activity and the foreground service share one state. */
class NetPilotApp : Application() {
    val controller by lazy { FailoverController(this, MainScope()) }
}
