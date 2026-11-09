package com.ubuntumobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Flathub apps the store offers, and how it shows their descriptions. */
class FlathubApiTest {
    @Test
    fun pcOnlyAppsDoNotRunOnArm() {
        val steam = FlathubHit("com.valvesoftware.Steam", "Steam", "", null, arches = listOf("x86_64"))
        assertFalse(steam.runsOn("aarch64"))
        assertTrue(steam.runsOn("x86_64"))
        assertFalse(steam.runsOn(null))
    }

    @Test
    fun appsWithoutArchesAreOffered() {
        assertTrue(FlathubHit("org.x.Y", "Y", "", null).runsOn("aarch64"))
    }

    @Test
    fun descriptionsBecomePlainText() {
        val html = "<p>A chat app.</p>\n      <ul><li>Fast</li><li>Private &amp; free</li></ul>"
        assertEquals("A chat app.\n\n• Fast\n• Private & free", FlathubApi.plainText(html))
    }

    @Test
    fun steamSaysWhatToDoInstead() {
        val steam = FlathubHit("com.valvesoftware.Steam", "Steam", "", null, arches = listOf("x86_64")).toStoreApp()
        assertTrue("Get DroidDeck" in notForThisPhone(steam))
        assertTrue("Tap Play" in notForThisPhone(steam, droidDeckInstalled = true))
    }
}
