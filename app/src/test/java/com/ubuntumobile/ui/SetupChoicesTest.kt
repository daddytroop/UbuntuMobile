package com.ubuntumobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What onboarding writes is what linx-setup.sh reads, and "Set up again" reads it back. */
class SetupChoicesTest {
    @Test
    fun writesWhatTheSetupScriptReads() {
        val conf = SetupChoices(
            profile = SetupProfile.FULL,
            dark = true,
            accent = Accent.BLUE,
            browser = true,
            apps = setOf("gimp", "libreoffice-writer"),
            flathub = true,
            flatpaks = setOf("io.mpv.Mpv"),
        ).toConf()
        assertTrue(conf.contains("LINX_DARK=1\n"))
        assertTrue(conf.contains("LINX_ACCENT=blue\n"))
        assertTrue(conf.contains("LINX_FLATPAKS=\"io.mpv.Mpv\"\n"))
        // Writer brings the GTK integration with it.
        assertTrue(conf.contains("LINX_APPS=\"libreoffice-writer libreoffice-gtk3 gimp\"\n"))
    }

    @Test
    fun interestsTickTheirAppsAndAreReadBack() {
        val picked = SetupChoices(apps = emptySet()).withInterest(Interest.WORK, true).withInterest(Interest.MEDIA, true)
        // Ubuntu apps for work and media (Flathub ones depend on the processor).
        assertTrue("libreoffice-writer" in picked.apps)
        assertTrue("vlc" in picked.apps)
        val back = SetupChoices.fromConf(picked.toConf(), DesktopSize.STANDARD)
        assertEquals(setOf(Interest.WORK, Interest.MEDIA), back.interests)
        // Dropping one unticks its apps and keeps the other's.
        val less = picked.withInterest(Interest.MEDIA, false)
        assertTrue("vlc" !in less.apps)
        assertTrue("libreoffice-writer" in less.apps)
    }

    @Test
    fun flathubAppsAreLeftOutWhenFlathubIsOff() {
        val conf = SetupChoices(flathub = false, flatpaks = setOf("io.mpv.Mpv")).toConf()
        assertTrue(conf.contains("LINX_FLATHUB=0\n"))
        assertTrue(conf.contains("LINX_FLATPAKS=\"\"\n"))
    }

    @Test
    fun readsBackWhatItWrote() {
        val choices = SetupChoices(
            profile = SetupProfile.LIGHT,
            dark = true,
            accent = Accent.TEAL,
            desktopSize = DesktopSize.LARGE,
            browser = false,
            apps = setOf("vlc", "geany"),
            flathub = true,
            flatpaks = setOf("org.telegram.desktop"),
        )
        assertEquals(choices, SetupChoices.fromConf(choices.toConf(), DesktopSize.LARGE))
    }

    @Test
    fun aLighterSetupDownloadsLess() {
        val light = SetupChoices(browser = false, apps = SetupChoices.defaultApps(SetupProfile.LIGHT))
        val full = SetupChoices(apps = SetupChoices.defaultApps(SetupProfile.FULL))
        assertTrue(light.downloadMb < full.downloadMb)
    }
}
