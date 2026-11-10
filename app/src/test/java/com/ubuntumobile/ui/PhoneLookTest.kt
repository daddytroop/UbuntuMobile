package com.ubuntumobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which Yaru accent the phone's Material You colour becomes. */
class PhoneLookTest {
    @Test
    fun everyAccentFindsItself() {
        Accent.entries.forEach { assertEquals(it, PhoneLook.nearestAccent(it.color.toInt())) }
    }

    @Test
    fun phoneColoursGoToTheNearestHue() {
        assertEquals(Accent.BLUE, PhoneLook.nearestAccent(0xFF4285F4.toInt())) // Google blue
        assertEquals(Accent.TEAL, PhoneLook.nearestAccent(0xFF00897B.toInt()))
        assertEquals(Accent.RED, PhoneLook.nearestAccent(0xFFE53935.toInt()))
        assertEquals(Accent.SAGE, PhoneLook.nearestAccent(0xFF8A8F8B.toInt())) // greyish
    }
}
