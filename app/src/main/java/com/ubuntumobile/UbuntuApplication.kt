package com.ubuntumobile

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application entry point.
 *
 * Besides the usual start-up work, this installs an uncaught-exception
 * handler that writes the stack trace to `filesDir/crash.log`. That file can
 * be pulled with
 *
 * ```
 * adb shell run-as com.ubuntumobile cat files/crash.log
 * ```
 *
 * which makes crashes on a device diagnosable without a debugger.
 */
class UbuntuApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // The install log is written to a separate file, but folding it into
        // crash.log means one command is enough to diagnose a failed install.
        mirrorInstallLog()
    }

    /** Copy the tail of the install log into crash.log so one file has it all. */
    private fun mirrorInstallLog() {
        try {
            val installLog = File(filesDir, "install.log")
            if (!installLog.exists()) return
            val tail = installLog.readText().takeLast(16 * 1024)
            if (tail.isBlank()) return
            val crash = File(filesDir, "crash.log")
            crash.appendText("\n=== install log ===\n$tail\n")
        } catch (_: Throwable) {
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrashLog(thread, error)
            } catch (_: Throwable) {
                // Never let the crash handler itself throw.
            }
            // Hand back to the platform so the app still shows the crash
            // dialog / gets killed as usual.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrashLog(thread: Thread, error: Throwable) {
        val file = File(filesDir, "crash.log")
        val stack = StringWriter().also { sw ->
            error.printStackTrace(PrintWriter(sw))
            sw.toString()
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("=== $stamp ===\n")
            append("thread: ${thread.name}\n")
            append(stack)
            append('\n')
        }
        // Keep the log bounded; a crash loop should not fill the device.
        if (file.length() > 512 * 1024) file.delete()
        file.appendText(text)
        Log.e(TAG, "uncaught exception on ${thread.name}", error)
    }

    private companion object {
        const val TAG = "UbuntuApplication"
    }
}
