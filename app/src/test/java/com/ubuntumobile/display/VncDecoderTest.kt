package com.ubuntumobile.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the RFB decoder pixel-for-pixel against a fake server.
 *
 * A live X server test only proves the client connects; this one proves the
 * bytes are turned into the right colours, which is where byte-order and
 * Hextile mistakes hide.
 */
class VncDecoderTest {

    private fun decodeWith(
        encoding: Int,
        format: VncClient.PixelFormat =
            VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0),
    ): Pair<IntArray, IntArray> {
        val server = FakeVncServer(encoding = encoding, format = format)
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()

            val latch = CountDownLatch(1)
            var rects = 0
            client.onUpdate = { rects += it.size; latch.countDown() }

            val reader = Thread {
                try { client.runLoop() } catch (_: Exception) {}
            }
            reader.isDaemon = true
            reader.start()

            client.requestFullUpdate()
            assertTrue("no update arrived (encoding $encoding)", latch.await(10, TimeUnit.SECONDS))
            assertTrue("no rectangles decoded (encoding $encoding)", rects > 0)
            return client.framebuffer to server.expectedDecoded()
        } finally {
            server.stop()
        }
    }

    private fun assertMatches(label: String, encoding: Int, format: VncClient.PixelFormat) {
        val (decoded, expected) = decodeWith(encoding, format)
        assertEquals("$label: framebuffer size", expected.size, decoded.size)
        var mismatches = 0
        for (i in expected.indices) {
            if (expected[i] != decoded[i]) mismatches++
        }
        println("$label: ${decoded.size} pixels, $mismatches mismatches")
        assertEquals("$label: every pixel must match", 0, mismatches)
    }

    @Test
    fun decodesRawPixelsExactly() {
        assertMatches(
            "raw/32bpp-bgrx", 0,
            VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0),
        )
    }

    @Test
    fun decodesHextileTilesExactly() {
        assertMatches(
            "hextile/32bpp-bgrx", 5,
            VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0),
        )
    }

    @Test
    fun decodesBigEndianFormat() {
        assertMatches(
            "raw/32bpp-bigendian", 0,
            VncClient.PixelFormat(32, 24, true, true, 255, 255, 255, 16, 8, 0),
        )
    }

    @Test
    fun decodesRgb565Format() {
        assertMatches(
            "raw/16bpp-rgb565", 0,
            VncClient.PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0),
        )
    }

    @Test
    fun decodesAlternateChannelOrder() {
        assertMatches(
            "raw/32bpp-rgbx", 0,
            VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 0, 8, 16),
        )
    }

    @Test
    fun reportsTheDesktopSize() {
        val server = FakeVncServer(width = 320, height = 240)
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()
            assertEquals(320, client.width)
            assertEquals(240, client.height)
            assertEquals(320 * 240, client.framebuffer.size)
            client.close()
        } finally {
            server.stop()
        }
    }
}
