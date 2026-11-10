package com.ubuntumobile.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * The phone's own look and language, for Linux to follow: dark mode, the
 * colour Android picked from the wallpaper (Android 12 and newer), and the
 * language and region.
 */
object PhoneLook {
    fun dark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The Yaru accent nearest the phone's Material You colour; null before Android 12. */
    fun accent(context: Context): Accent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val argb = context.getColor(android.R.color.system_accent1_500)
        return nearestAccent(argb)
    }

    /** "de_DE" for German in Germany: the phone's language, as glibc names locales. */
    fun localeName(): String {
        val locale = java.util.Locale.getDefault()
        val country = locale.country.ifBlank { defaultCountry[locale.language] ?: "" }
        return if (country.isBlank()) locale.language else "${locale.language}_$country"
    }

    /** For languages set without a region. */
    private val defaultCountry = mapOf(
        "en" to "US", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR",
        "nl" to "NL", "ja" to "JP", "ko" to "KR", "zh" to "CN", "ru" to "RU", "pl" to "PL",
    )

    /**
     * The accent whose colour is nearest [argb]. Hue decides for colourful
     * colours; greyish ones go to Sage, the one muted accent.
     */
    fun nearestAccent(argb: Int): Accent {
        val hsv = FloatArray(3)
        hsv(argb, hsv)
        if (hsv[1] < 0.22f) return Accent.SAGE
        return Accent.entries.filter { it != Accent.SAGE }.minBy { accent ->
            val other = FloatArray(3)
            hsv(accent.color.toInt(), other)
            val d = kotlin.math.abs(hsv[0] - other[0])
            minOf(d, 360f - d)
        }
    }

    /** android.graphics.Color.colorToHSV, without Android (unit tests run on the JVM). */
    private fun hsv(argb: Int, out: FloatArray) {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        out[0] = if (h < 0) h + 360f else h
        out[1] = if (max == 0f) 0f else delta / max
        out[2] = max
    }
}
