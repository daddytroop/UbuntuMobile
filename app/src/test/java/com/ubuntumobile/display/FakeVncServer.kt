package com.ubuntumobile.display

import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * A minimal RFB server used by the tests.
 *
 * It speaks just enough of the protocol to hand the client a known image, so
 * the decoder can be verified pixel-for-pixel without needing a real X
 * server. This is a hermetic check that catches byte-order and encoding
 * mistakes that a "does it connect" test would miss.
 */
class FakeVncServer(
    val width: Int = 64,
    val height: Int = 48,
    /** 0 = Raw, 5 = Hextile. */
    private val encoding: Int = 0,
    /** The pixel format the server announces. */
    private val format: VncClient.PixelFormat =
        VncClient.PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0),
) {

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    val started = CountDownLatch(1)

    /** The pixels the server "paints", as ARGB ints. */
    val source: IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        // A deterministic gradient with a marker in the corner.
        val r = (x * 255) / (width - 1)
        val g = (y * 255) / (height - 1)
        val b = if (x < 4 && y < 4) 0xFF else 0x40
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Port the server is listening on. */
    var port: Int = 0
        private set

    fun start() {
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        val t = Thread {
            try {
                ss.accept().use { socket -> serve(socket) }
            } catch (e: Exception) {
                trace("error: $e")
            }
        }
        t.isDaemon = true
        thread = t
        t.start()
        started.countDown()
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun trace(message: String) {
        try {
            val f = File("build/fake-vnc-trace.txt")
            f.parentFile?.mkdirs()
            f.appendText(message + "\n")
        } catch (_: Exception) {
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        trace("--- connection ---")
        out.write("RFB 003.008\n".toByteArray())
        out.flush()
        // The protocol version is a fixed 12-byte string with no length prefix.
        input.readFully(ByteArray(12))
        out.write(byteArrayOf(1, 1))   // one security type: None
        out.flush()
        input.readUnsignedByte()   // chosen security type
        out.write(byteArrayOf(0, 0, 0, 0)) // SecurityResult = OK
        out.flush()
        input.readUnsignedByte()   // ClientInit shared flag

        // ServerInit
        out.write(width.toShortBigEndian())
        out.write(height.toShortBigEndian())
        out.write(pixelFormatBytes())
        out.write(byteArrayOf(0, 0, 0, 0)) // name length 0
        out.flush()
        trace("sent ServerInit")

        while (true) {
            val type = input.readUnsignedByte()
            trace("client message type=$type")
            when (type) {
                0 -> { /* SetPixelFormat */ input.readFully(ByteArray(19)) }
                2 -> { /* SetEncodings */
                    input.readUnsignedByte()
                    val count = input.readUnsignedShort()
                    input.readFully(ByteArray(count * 4))
                }
                3 -> { // FramebufferUpdateRequest
                    input.readUnsignedByte()
                    input.readUnsignedShort(); input.readUnsignedShort()
                    input.readUnsignedShort(); input.readUnsignedShort()
                    trace("sending update (encoding=$encoding)")
                    sendUpdate(out)
                }
                else -> {
                    trace("unexpected message $type, closing")
                    return
                }
            }
        }
    }

    private fun sendUpdate(out: OutputStream) {
        out.write(0)          // FramebufferUpdate
        out.write(0)          // padding
        out.write(1.toShortBigEndian()) // one rectangle
        out.write(0.toShortBigEndian())
        out.write(0.toShortBigEndian())
        out.write(width.toShortBigEndian())
        out.write(height.toShortBigEndian())
        out.write(encoding.toBigEndian())

        if (encoding == 0) {
            out.write(rawBytes())
        } else {
            out.write(hextileBytes())
        }
        out.flush()
    }

    /** The framebuffer encoded in the server's pixel format. */
    private fun rawBytes(): ByteArray {
        val bpp = (format.bitsPerPixel + 7) / 8
        val bytes = ByteArray(width * height * bpp)
        var i = 0
        for (p in source) {
            val value = encode(p)
            for (b in 0 until bpp) {
                val shift = if (format.bigEndian) (bpp - 1 - b) * 8 else b * 8
                bytes[i++] = ((value shr shift) and 0xFF).toByte()
            }
        }
        return bytes
    }

    /**
     * What the client should end up with after a round trip.
     *
     * For 32bpp this equals [source] exactly. For 16bpp the server has to
     * quantise the colour channels, so the expectation is the quantised
     * value decoded back to ARGB.
     */
    fun expectedDecoded(): IntArray = IntArray(source.size) { i ->
        format.toArgb(encode(source[i]))
    }

    /** Convert an ARGB pixel into the server's pixel value. */
    private fun encode(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (!format.trueColour) return r
        val rv = r * format.redMax / 255
        val gv = g * format.greenMax / 255
        val bv = b * format.blueMax / 255
        return (rv shl format.redShift) or (gv shl format.greenShift) or (bv shl format.blueShift)
    }

    /**
     * Hextile encoding of the same image: each 16x16 tile is sent raw, which
     * is what a real server does for content it cannot compress.
     */
    private fun hextileBytes(): ByteArray {
        val bpp = (format.bitsPerPixel + 7) / 8
        val out = java.io.ByteArrayOutputStream()
        var ty = 0
        while (ty < height) {
            val th = minOf(16, height - ty)
            var tx = 0
            while (tx < width) {
                val tw = minOf(16, width - tx)
                out.write(0x01) // raw tile
                for (row in 0 until th) {
                    for (col in 0 until tw) {
                        val value = encode(source[(ty + row) * width + (tx + col)])
                        for (b in 0 until bpp) {
                            val shift = if (format.bigEndian) (bpp - 1 - b) * 8 else b * 8
                            out.write((value shr shift) and 0xFF)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
        return out.toByteArray()
    }

    private fun Int.toShortBigEndian(): ByteArray = byteArrayOf(
        ((this shr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte(),
    )

    private fun Int.toBigEndian(): ByteArray = byteArrayOf(
        ((this shr 24) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte(),
    )

    /** 16-byte pixel format, as announced in ServerInit. */
    private fun pixelFormatBytes(): ByteArray = byteArrayOf(
        format.bitsPerPixel.toByte(),
        format.depth.toByte(),
        (if (format.bigEndian) 1 else 0).toByte(),
        (if (format.trueColour) 1 else 0).toByte(),
        ((format.redMax shr 8) and 0xFF).toByte(), (format.redMax and 0xFF).toByte(),
        ((format.greenMax shr 8) and 0xFF).toByte(), (format.greenMax and 0xFF).toByte(),
        ((format.blueMax shr 8) and 0xFF).toByte(), (format.blueMax and 0xFF).toByte(),
        format.redShift.toByte(),
        format.greenShift.toByte(),
        format.blueShift.toByte(),
        0, 0, 0,
    )
}
