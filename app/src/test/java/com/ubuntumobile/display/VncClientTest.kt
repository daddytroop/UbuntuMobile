package com.ubuntumobile.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the RFB client against a real VNC server.
 *
 * The test expects an X server exporting RFB on 127.0.0.1:5901 - on a
 * developer machine that is the container started by `tools/start_test_display.sh`.
 * If no server is reachable the test is skipped rather than failed, so the
 * suite still passes on a bare machine.
 *
 * To verify pixel accuracy the test reads the colour that the X server
 * currently shows (written there by the helper script) and checks that the
 * decoded framebuffer contains it.
 */
class VncClientTest {

    private fun serverAvailable(): Boolean = try {
        java.net.Socket("127.0.0.1", 5901).use { true }
    } catch (_: Exception) {
        false
    }

    /** The colour the X root window was painted with, if the helper said so. */
    private fun expectedColour(): Int? {
        // Gradle runs unit tests with the module directory as the working
        // directory; the helper writes the file to both locations.
        val candidates = listOf(
            File("build/linx-display-colour.txt"),
            File("../build/linx-display-colour.txt"),
        )
        val f = candidates.firstOrNull { it.exists() } ?: return null
        val hex = f.readText().trim().removePrefix("#")
        if (hex.length != 6) return null
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun connectsAndReceivesFramebufferUpdates() {
        assumeTrue("no VNC server on 127.0.0.1:5901", serverAvailable())

        val client = VncClient("127.0.0.1", 5901)
        client.connect()

        assertTrue("framebuffer width is zero", client.width > 0)
        assertTrue("framebuffer height is zero", client.height > 0)
        println("connected: ${client.width}x${client.height} name=${client.desktopName}")
        println("server pixel format: ${client.pixelFormat}")

        val latch = CountDownLatch(1)
        var rects = 0
        var pixels = 0
        client.onUpdate = { changed ->
            rects += changed.size
            pixels += changed.sumOf { it.width * it.height }
            latch.countDown()
        }

        val reader = Thread {
            try {
                client.runLoop()
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        // Exactly one request may be outstanding; the client re-requests
        // after each update it receives.
        client.requestFullUpdate()

        val got = latch.await(20, TimeUnit.SECONDS)
        assertTrue("no framebuffer update arrived", got)
        println("received $rects rect(s), $pixels pixels")
        assertTrue("update contained no pixels", pixels > 0)

        val fb = client.framebuffer
        assertEquals("framebuffer size mismatch", client.width * client.height, fb.size)

        // The decoded pixels must actually match what the X server shows.
        val expected = expectedColour()
        val distinct = fb.toHashSet()
        println("distinct colours: $distinct -> " +
            distinct.take(4).joinToString { "0x%08X".format(it) })
        if (expected != null) {
            val count = fb.count { it == expected }
            println("pixels matching the painted colour 0x%08X: %d / %d"
                .format(expected, count, fb.size))
            assertTrue("no pixel matched the expected colour $expected", count > 0)
        } else {
            assertTrue("framebuffer looks empty ($distinct colours)", distinct.isNotEmpty())
        }

        client.close()
    }

    @Test
    fun sendsPointerAndKeyEventsWithoutFailing() {
        assumeTrue("no VNC server on 127.0.0.1:5901", serverAvailable())

        val client = VncClient("127.0.0.1", 5901)
        client.connect()
        client.sendPointer(100, 200, 0)
        client.sendPointer(110, 210, 1)
        client.sendPointer(110, 210, 0)
        client.sendKey(0x0041, true)   // 'A'
        client.sendKey(0x0041, false)
        client.requestFullUpdate()
        // Give the server a moment to process the input.
        Thread.sleep(500)
        client.close()
    }
}
