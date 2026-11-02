package com.ubuntumobile.display

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/**
 * Replays an RFB stream recorded on a phone through the decoder.
 *
 * Debug builds save what the server sent to cache/vnc-stream.bin when
 * files/vnc-capture exists. Pull that file and run
 *
 *   LINX_VNC_REPLAY=/path/to/vnc-stream.bin ./gradlew :app:testAmd64DebugUnitTest --tests '*VncReplayTest*'
 *
 * The decoder has to reach the end of the recording without losing sync.
 */
class VncReplayTest {

    @Test
    fun decodesARecordedStreamToTheEnd() {
        val path = System.getenv("LINX_VNC_REPLAY")
        assumeTrue("set LINX_VNC_REPLAY to a recorded stream", !path.isNullOrBlank())
        val recording = File(path!!).readBytes()

        val server = ServerSocket(0)
        Thread {
            server.accept().use { socket ->
                // Swallow whatever the client says; the recording already
                // contains the server's side of the conversation.
                Thread {
                    try {
                        socket.getInputStream().readBytes()
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
                socket.getOutputStream().write(recording)
                socket.getOutputStream().flush()
                Thread.sleep(500)
            }
        }.apply { isDaemon = true }.start()

        val client = VncClient("127.0.0.1", server.localPort)
        client.connect()
        var updates = 0
        client.onUpdate = { updates++ }
        val failure = try {
            client.runLoop()
            null
        } catch (e: Exception) {
            e
        }
        println("replay: ${recording.size} bytes, $updates updates, ended with $failure")
        // The recording simply stops, so the stream ends in EOF (or a
        // closed socket). A decoding error means the decoder lost sync.
        assertTrue(
            "the decoder lost sync: $failure",
            failure == null || failure is java.io.EOFException || failure is java.net.SocketException,
        )
        server.close()
    }
}
