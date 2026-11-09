package com.ubuntumobile.ui

import com.ubuntumobile.display.SetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The developer tools and wallpaper in setup.conf, setup's progress, and the launcher's app list. */
class SetupExtrasTest {
    @Test
    fun devToolsGoInWithTheAptPackagesAndComeBack() {
        val choices = SetupChoices(apps = setOf("geany"), devTools = setOf("gh", "btop"), wallpaper = Wallpaper.DUNES)
        val conf = choices.toConf()
        assertTrue(conf.contains("LINX_APPS=\"geany gh btop\"\n"))
        assertTrue(conf.contains("LINX_WALLPAPER=dunes\n"))
        val back = SetupChoices.fromConf(conf, DesktopSize.STANDARD)
        assertEquals(setOf("gh", "btop"), back.devTools)
        assertEquals(setOf("geany"), back.apps)
        assertEquals(Wallpaper.DUNES, back.wallpaper)
    }

    @Test
    fun anUnknownWallpaperFallsBackToTheDefault() {
        assertEquals(Wallpaper.AUBERGINE, Wallpaper.byId("nope"))
        assertEquals(Wallpaper.AUBERGINE, SetupChoices.fromConf("LINX_DARK=1\n", DesktopSize.STANDARD).wallpaper)
    }

    @Test
    fun progressCombinesTheStepAndApt() {
        val log = listOf("STEP 1/4 Sound and the Ubuntu look", "STEP 2/4 Firefox")
        // Half-way through the download is a quarter of the step.
        val downloading = SetupStatus.parse(log, listOf("dlstatus:12:50.0:Retrieving file 12 of 24"))!!
        assertEquals(2, downloading.step)
        assertEquals("Firefox", downloading.title)
        assertEquals("Downloading 50%", downloading.detail)
        assertEquals((1 + 0.25f) / 4, downloading.fraction, 0.001f)

        val installing = SetupStatus.parse(log, listOf("dlstatus:24:100:done", "pmstatus:firefox:50.0:Installing firefox"))!!
        assertEquals("Installing firefox", installing.detail)
        assertEquals((1 + 0.75f) / 4, installing.fraction, 0.001f)

        val flatpak = SetupStatus.parse(listOf("STEP 4/4 Apps from Flathub"), listOf("flatpak:12%", "flatpak:40%"))!!
        assertEquals((3 + 0.4f) / 4, flatpak.fraction, 0.001f)
    }

    @Test
    fun noStepYetMeansNoProgress() {
        assertNull(SetupStatus.parse(listOf("something else"), emptyList()))
    }

    @Test
    fun theLauncherReadsLinxAppsLines() {
        val apps = AppCatalog.parseInstalled(
            "firefox|Firefox|Fast and private browser|app|/usr/share/icons/hicolor/128x128/apps/firefox.png\n" +
                "io.mpv.Mpv|mpv Media Player|Play movies|flatpak|\n" +
                "xfce4-terminal|Terminal|Terminal Emulator|system|/x.svg\n" +
                "broken line\n",
        )
        assertEquals(listOf("firefox", "io.mpv.Mpv", "xfce4-terminal"), apps.map { it.id })
        assertEquals(AppEntry.Category.FLATPAK, apps[1].category)
        assertNull(apps[1].iconPath)
        assertEquals(AppEntry.Category.SYSTEM, apps[2].category)
        assertEquals(listOf("/usr/bin/python3", "/usr/local/lib/linx/linx-apps.py", "--run", "firefox"), apps[0].command)
    }
}
