package com.ubuntumobile.ui

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences

/**
 * How hard the desktop works, by how powerful the phone is. Named for
 * phones rather than for what each turns off, so the choice is easy; the
 * app recommends one from the phone's memory ([recommended]).
 *
 * What each level does, in the app (the frame rate, [VncClient]) and in
 * Linux (linx-performance.sh: the desktop icons, printing, animations):
 */
enum class Performance(
    val id: String,
    val label: String,
    val phones: String,
    val detail: String,
    /** Screen updates a second. */
    val fps: Int,
) {
    BASIC(
        "basic", "Basic", "Budget and older phones",
        "30 frames a second, no desktop icons, printing or animations: more memory and battery for your apps.",
        30,
    ),
    BALANCED(
        "balanced", "Balanced", "Most phones",
        "60 frames a second with everything on, and no animations.",
        60,
    ),
    FAST(
        "fast", "Fast", "Flagship phones",
        "60 frames a second, everything on, with animations.",
        60,
    ),
    ;

    companion object {
        private const val KEY = "performance"

        /** The phone's memory in whole gigabytes, as the box says (a "6 GB" phone reports a little less). */
        fun memoryGb(context: Context): Int {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            return boxSize(info.totalMem)
        }

        /** The size a phone with [bytes] of usable memory is sold as: Android keeps some for itself. */
        internal fun boxSize(bytes: Long): Int {
            val gib = bytes / (1024.0 * 1024 * 1024)
            return listOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32).firstOrNull { it >= gib } ?: Math.ceil(gib).toInt()
        }

        /** What suits this phone: by its memory, which is what runs out first. */
        fun recommended(context: Context): Performance {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.isLowRamDevice) return BASIC
            return forMemory(memoryGb(context))
        }

        internal fun forMemory(gb: Int): Performance = when {
            gb <= 4 -> BASIC
            gb <= 8 -> BALANCED
            else -> FAST
        }

        fun saved(prefs: SharedPreferences, context: Context): Performance =
            entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: recommended(context)

        fun save(prefs: SharedPreferences, level: Performance) {
            prefs.edit().putString(KEY, level.name).apply()
        }
    }
}
