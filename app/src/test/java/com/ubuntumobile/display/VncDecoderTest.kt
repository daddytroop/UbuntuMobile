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
        hextileSubrects: Boolean = false,
    ): Pair<IntArray, IntArray> {
        val server = FakeVncServer(encoding = encoding, format = format, hextileSubrects = hextileSubrects)
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

    private fun assertMatches(
        label: String,
        encoding: Int,
        format: VncClient.PixelFormat,
        hextileSubrects: Boolean = false,
    ) {
        val (decoded, expected) = decodeWith(encoding, format, hextileSubrects)
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
    fun decodesHextileSubrectanglesExactly() {
        // Background, solid tiles and coloured sub-rectangles: the count is
        // a byte of its own and each sub-rectangle is two bytes of nibbles.
        assertMatches(
            "hextile-subrects/32bpp-bgrx", 5,
            VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0),
            hextileSubrects = true,
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
    fun skipsClipboardMessagesWithoutLosingSync() {
        // x11vnc sends ServerCutText whenever the clipboard changes. It has
        // three bytes of padding before its length; missing them shifts
        // every following message.
        val server = FakeVncServer(sendCutText = true)
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()
            val frames = CountDownLatch(3)
            client.onUpdate = { frames.countDown() }
            var failure: Throwable? = null
            Thread {
                try { client.runLoop() } catch (e: Throwable) { failure = e }
            }.apply { isDaemon = true; start() }
            client.requestFullUpdate()
            assertTrue(
                "frames stopped after a clipboard message: $failure",
                frames.await(10, TimeUnit.SECONDS),
            )
            assertEquals(server.expectedDecoded().toList(), client.framebuffer.toList())
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun keyEventsHaveTheRightLengthSoLaterMessagesStillParse() {
        // A key event is 8 bytes. One byte short and the server reads the
        // next message from the wrong place (x11vnc drops the client).
        val server = FakeVncServer()
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()
            client.sendKey(0x6c, true)
            client.sendKey(0x6c, false)
            client.sendPointer(10, 20, 1)
            val deadline = System.currentTimeMillis() + 5000
            while (server.pointerEvents.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf(0x6c, 0x6c), server.keyEvents.toList())
            val pointer = server.pointerEvents.single()
            assertEquals(1, pointer[0].toInt())
            assertEquals(10, ((pointer[1].toInt() and 0xFF) shl 8) or (pointer[2].toInt() and 0xFF))
            assertEquals(20, ((pointer[3].toInt() and 0xFF) shl 8) or (pointer[4].toInt() and 0xFF))
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun asksTheServerForANewSizeAndFollowsIt() {
        // How the desktop follows a rotation: the client asks Xvnc for a
        // new size (SetDesktopSize) and switches to the size it answers with.
        val server = FakeVncServer(width = 64, height = 48, resizable = true)
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()
            val resized = CountDownLatch(1)
            val repainted = CountDownLatch(1)
            client.onResize = { _, _ -> resized.countDown() }
            client.onUpdate = { if (client.width == 48) repainted.countDown() }
            var failure: Throwable? = null
            Thread {
                try { client.runLoop() } catch (e: Throwable) { failure = e }
            }.apply { isDaemon = true; start() }
            client.requestFullUpdate()

            val deadline = System.currentTimeMillis() + 5000
            while (!client.canResize && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue("the server's resize support was not noticed: $failure", client.canResize)
            assertTrue(client.requestDesktopSize(48, 64))

            assertTrue("the new size never arrived: $failure", resized.await(5, TimeUnit.SECONDS))
            assertTrue("the new size was never painted: $failure", repainted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(48 to 64), server.desktopSizeRequests.toList())
            assertEquals(48, client.width)
            assertEquals(64, client.height)
            assertEquals(48 * 64, client.framebuffer.size)
            assertTrue(client.framebuffer.all { it == FakeVncServer.RESIZED_COLOUR })
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun doesNotAskAServerThatCannotResize() {
        // x11vnc never announces ExtendedDesktopSize; a SetDesktopSize it
        // does not understand would end the connection.
        val server = FakeVncServer()
        server.start()
        try {
            val client = VncClient("127.0.0.1", server.port)
            client.connect()
            val frame = CountDownLatch(1)
            client.onUpdate = { frame.countDown() }
            Thread { try { client.runLoop() } catch (_: Exception) {} }.apply { isDaemon = true; start() }
            client.requestFullUpdate()
            assertTrue(frame.await(5, TimeUnit.SECONDS))
            assertEquals(false, client.canResize)
            assertEquals(false, client.requestDesktopSize(48, 64))
            client.close()
        } finally {
            server.stop()
        }
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
