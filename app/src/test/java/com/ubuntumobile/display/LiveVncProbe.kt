package com.ubuntumobile.display

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to a real RFB server - the app's own Xvfb + x11vnc stack - instead
 * of the fake one.
 *
 * The fake server only produces what the test author thought the protocol
 * was; the real one is the actual reference. Start the display stack from
 * a rootfs (for instance in a WSL chroot, see the README) and run
 *
 *   LINX_LIVE_VNC=127.0.0.1:5901 ./gradlew :app:testAmd64DebugUnitTest --tests '*LiveVncProbe*'
 *
 * The decoded desktop is written to build/live-vnc.ppm.
 */
class LiveVncProbe {

    @Test
    fun decodesARealServerWithoutLosingSync() {
        val target = System.getenv("LINX_LIVE_VNC")
        assumeTrue("set LINX_LIVE_VNC=host:port to run against a real server", !target.isNullOrBlank())
        val (host, port) = target!!.split(":").let { it[0] to it[1].toInt() }

        val client = VncClient(host, port)
        client.connect()
        val updates = AtomicInteger()
        client.onUpdate = { updates.incrementAndGet() }

        var failure: Throwable? = null
        val reader = Thread {
            try {
                client.runLoop()
            } catch (e: Throwable) {
                failure = e
            }
        }
        reader.isDaemon = true
        reader.start()
        client.requestFullUpdate()

        // LINX_LIVE_RESIZE=1466x720 asks Xvnc for a new size first, as the
        // app does when the phone rotates.
        System.getenv("LINX_LIVE_RESIZE")?.split("x")?.map { it.toInt() }?.let { (w, h) ->
            val deadline = System.currentTimeMillis() + 10_000
            while (!client.canResize && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("the server cannot resize", client.requestDesktopSize(w, h))
            while ((client.width != w || client.height != h) && System.currentTimeMillis() < deadline + 10_000) {
                Thread.sleep(50)
            }
            // Give the desktop time to rearrange itself for the new size.
            Thread.sleep(4000)
            client.requestFullUpdate()
        }

        // Wiggle the pointer so the server has damage to send.
        repeat(System.getenv("LINX_LIVE_STEPS")?.toIntOrNull() ?: 40) { i ->
            Thread.sleep(250)
            // Across the desktop, then along the dock at the bottom, where
            // hovering produces small, detailed Hextile updates.
            val y = if (i % 40 < 20) 50 + (i % 20) * 40 else client.height - 50
            client.sendPointer(40 + (i % 20) * 32, y, 0)
        }
        if (System.getenv("LINX_LIVE_KEYS") != null) {
            // l, s, Return - as the app sends them for the soft keyboard.
            for (keysym in listOf(0x6c, 0x73, 0xFF0D)) {
                client.sendKey(keysym, true)
                client.sendKey(keysym, false)
            }
            Thread.sleep(3000)
        }
        client.close()
        reader.join(5000)

        writePpm(File("build/live-vnc.ppm"), client.width, client.height, client.framebuffer)
        println("live: ${client.width}x${client.height}, ${updates.get()} updates, failure=$failure")

        // A closed socket at the end is expected; anything else is a decoder bug.
        val unexpected = failure?.takeUnless { it is java.net.SocketException || it.message == "not connected" }
        assertNull("the decoder lost sync: $unexpected", unexpected)
        assertTrue("no framebuffer update arrived", updates.get() > 0)
    }
}

/** A binary PPM is the simplest image format that needs no imaging library. */
private fun writePpm(file: File, width: Int, height: Int, argb: IntArray) {
    file.parentFile?.mkdirs()
    file.outputStream().buffered().use { out ->
        out.write("P6\n$width $height\n255\n".toByteArray())
        for (p in argb) {
            out.write((p shr 16) and 0xFF)
            out.write((p shr 8) and 0xFF)
            out.write(p and 0xFF)
        }
    }
}
