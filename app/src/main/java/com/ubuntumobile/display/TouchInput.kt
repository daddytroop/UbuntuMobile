package com.ubuntumobile.display

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How a finger (or a pen) on the phone's screen works the Linux mouse.
 * Chosen from the menu (Controls → Touch mode) and kept.
 */
enum class TouchMode(val label: String, val detail: String, val hint: String) {
    /** The finger is the mouse, where it touches. */
    TOUCH(
        "Touch",
        "Tap where you want to click. Hold to right-click, drag to move things, two fingers to scroll.",
        "Tap to click · Hold to right-click · Two fingers to scroll",
    ),

    /** The screen is a laptop's trackpad: the finger moves the pointer. */
    TRACKPAD(
        "Trackpad",
        "Slide a finger to move the pointer, tap to click where it points. Two-finger tap right-clicks, " +
            "double-tap and slide drags. For small buttons and exact work.",
        "Slide to move · Tap to click · Two-finger tap to right-click",
    ),

    /** A pen points and clicks exactly; fingers only scroll. */
    STYLUS(
        "Stylus",
        "For a pen (S Pen and the like): hover to point, touch to click and draw, the pen's button to right-click. " +
            "Fingers only scroll, so a resting hand does nothing.",
        "Pen touches click · Pen button right-clicks · Fingers scroll",
    ),
}

object TouchInput {
    private const val PREFS = "settings"
    private const val KEY = "touch_mode"

    var mode by mutableStateOf(TouchMode.TOUCH)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = TouchMode.entries.firstOrNull { it.name == saved } ?: TouchMode.TOUCH
    }

    fun set(context: Context, value: TouchMode) {
        mode = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}
