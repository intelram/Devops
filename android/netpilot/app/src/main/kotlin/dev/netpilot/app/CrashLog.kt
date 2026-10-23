package dev.netpilot.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Local-only crash report: an uncaught exception is written to a private file, and the next
 * launch offers to show or copy it. Nothing is uploaded anywhere.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("NetPilot $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$sw")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}
