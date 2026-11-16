package com.ubuntumobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `versions compare by number`() {
        assertTrue(AppUpdate.newer("0.18.1", "0.18.0"))
        assertTrue(AppUpdate.newer("v0.19.0", "0.18.9"))
        assertTrue(AppUpdate.newer("0.18.10", "0.18.9"))
        assertTrue(AppUpdate.newer("1.0", "0.99.99"))
        assertFalse(AppUpdate.newer("0.18.0", "0.18.0"))
        assertFalse(AppUpdate.newer("0.17.4", "0.18.0"))
    }

    @Test
    fun `the release's apk is this phone's`() {
        val json = """
            {"tag_name": "v0.18.1", "body": "## Notes",
             "assets": [
               {"name": "app-amd64-release.apk", "size": 3, "browser_download_url": "https://x/amd64.apk"},
               {"name": "app-arm64-release.apk", "size": 410, "browser_download_url": "https://x/arm64.apk"}
             ]}
        """.trimIndent()
        val r = AppUpdate.parse(json, "arm64")!!
        assertEquals("0.18.1", r.version)
        assertEquals("https://x/arm64.apk", r.apkUrl)
        assertEquals(410L, r.size)
        assertNull(AppUpdate.parse(json, "armhf"))
    }
}
