package com.ubuntumobile.linux

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What Android does to an app's work in the background, and how to lift it.
 *
 * - Since Android 12 the "phantom process" monitor kills the extra
 *   processes an app starts - which is every Linux process - when there are
 *   more than 32, or when they use the processor in the background. Android
 *   14 and newer have a developer option to turn it off ("Disable child
 *   process restrictions"), which sets settings_enable_monitor_phantom_procs
 *   to false. DroidDeck asks for the same.
 * - Battery optimisation lets Android (Samsung's especially) stop the app
 *   altogether; the app can ask to be left out of it.
 */
object BackgroundLimits {
    /** Whether Android may kill Linux's processes (the child process restrictions are on). */
    fun childProcessesLimited(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        // The developer option is a feature flag: Settings keeps it as a
        // system property, not as a global setting (checked on Android 14).
        if (systemProperty("persist.sys.fflag.override.$PHANTOM_FLAG") == "false") return false
        val global = try {
            Settings.Global.getString(context.contentResolver, PHANTOM_FLAG)
        } catch (_: Exception) {
            null
        }
        return global != "false"
    }

    private const val PHANTOM_FLAG = "settings_enable_monitor_phantom_procs"

    /** A system property, through getprop (android.os.SystemProperties is hidden). */
    private fun systemProperty(name: String): String = try {
        val proc = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }.trim().also { proc.waitFor() }
    } catch (_: Exception) {
        ""
    }

    /** Whether the developer option exists (Android 14 and newer). */
    val canTurnOffInSettings: Boolean get() = Build.VERSION.SDK_INT >= 34

    fun batteryRestricted(context: Context): Boolean =
        !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openDeveloperOptions(context: Context) {
        open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            || open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }

    fun askForBattery(context: Context) {
        @Suppress("BatteryLife") // the app runs a whole Linux system: this is what it is for
        val asked = open(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
        if (!asked) open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
