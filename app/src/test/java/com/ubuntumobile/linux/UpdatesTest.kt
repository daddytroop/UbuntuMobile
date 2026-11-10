package com.ubuntumobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading linx-updates.sh's list and its progress. */
class UpdatesTest {
    @Test
    fun readsBothKinds() {
        val out = "deb|firefox|145.0\nflatpak|io.mpv.Mpv|\nnoise\n"
        assertEquals(
            listOf(UpdateItem(false, "firefox", "145.0"), UpdateItem(true, "io.mpv.Mpv", "")),
            Updates.parse(out),
        )
    }

    @Test
    fun progressFromAptAndFlatpak() {
        assertEquals(42, Updates.progress("dlstatus:3:42.5:Retrieving file 3 of 9"))
        assertEquals(87, Updates.progress("pmstatus:firefox:87.0:Installing firefox"))
        assertEquals(13, Updates.progress("flatpak:13%"))
        assertNull(Updates.progress("something else"))
    }
}
