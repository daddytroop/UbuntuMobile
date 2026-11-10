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
    /**
     * Send Hextile tiles as a background plus coloured sub-rectangles (and
     * solid tiles) instead of raw pixels. This is what real servers send
     * for desktop content, so the image is made of 4x4 blocks that can be
     * encoded that way.
     */
    private val hextileSubrects: Boolean = false,
    /** Send a clipboard update (ServerCutText) before every frame, as x11vnc does. */
    private val sendCutText: Boolean = false,
    /**
     * Take part in resizing the way Xvnc does: announce ExtendedDesktopSize
     * in the first update and honour SetDesktopSize. After a resize the
     * framebuffer is a single colour, [RESIZED_COLOUR].
     */
    private val resizable: Boolean = false,
    /**
     * After the first frame, send a CopyRect that moves a block onto an
     * overlapping place below and to the right, as dragging a window does.
     */
    private val copyRectAfterFirstFrame: Boolean = false,
    /**
     * Speak the extended clipboard the way Xvnc does: announce it when the
     * client asks for it, say the clipboard changed before every frame, and
     * send [EXTENDED_TEXT] (UTF-8) when the client asks for it.
     */
    private val extendedClipboard: Boolean = false,
) {

    /** The block the CopyRect moves: source x, y, then w, h, then destination x, y. */
    val copy = intArrayOf(4, 6, 24, 20, 10, 14)
    private var framesSent = 0

    companion object {
        const val RESIZED_COLOUR = 0xFF336699.toInt()
        const val EXTENDED_TEXT = "Grüße, 你好, привет 👋\nline two"
    }

    /** The sizes the client asked for with SetDesktopSize. */
    val desktopSizeRequests: MutableList<Pair<Int, Int>> =
        java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

    private var currentWidth = width
    private var currentHeight = height
    private var announcedSize = false
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    val started = CountDownLatch(1)

    /** The pixels the server "paints", as ARGB ints. */
    val source: IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        if (hextileSubrects) return@IntArray blockColour(x, y)
        // A deterministic gradient with a marker in the corner.
        val r = (x * 255) / (width - 1)
        val g = (y * 255) / (height - 1)
        val b = if (x < 4 && y < 4) 0xFF else 0x40
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Port the server is listening on. */
    var port: Int = 0
        private set

    /**
     * The pointer events the client sent, as
     * [mask, x-high, x-low, y-high, y-low].
     */
    val pointerEvents: MutableList<ByteArray> =
        java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

    /** The clipboard texts the client sent (ClientCutText). */
    val clipboardTexts: MutableList<String> =
        java.util.Collections.synchronizedList(mutableListOf<String>())

    /** The keysyms of the key events the client sent. */
    val keyEvents: MutableList<Int> =
        java.util.Collections.synchronizedList(mutableListOf<Int>())

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
                    val encodings = DataInputStream(ByteArray(count * 4).also { input.readFully(it) }.inputStream())
                    val asked = (0 until count).map { encodings.readInt() }
                    if (extendedClipboard && VncClient.ENCODING_EXTENDED_CLIPBOARD in asked) {
                        // Caps: text, up to 10 MB.
                        sendExtended(out, VncClient.CLIP_CAPS or VncClient.CLIP_TEXT, 10_000_000.toBigEndian())
                    }
                }
                3 -> { // FramebufferUpdateRequest
                    input.readUnsignedByte()
                    input.readUnsignedShort(); input.readUnsignedShort()
                    input.readUnsignedShort(); input.readUnsignedShort()
                    trace("sending update (encoding=$encoding)")
                    sendUpdate(out)
                }
                4 -> { // KeyEvent
                    input.readUnsignedByte()          // down flag
                    input.readUnsignedShort()         // padding
                    keyEvents.add(input.readInt())     // keysym
                    trace("key event")
                }
                5 -> { // PointerEvent
                    val mask = input.readUnsignedByte()
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    pointerEvents.add(
                        byteArrayOf(
                            mask.toByte(),
                            (x shr 8).toByte(), x.toByte(),
                            (y shr 8).toByte(), y.toByte(),
                        )
                    )
                    trace("pointer event mask=$mask at $x,$y")
                }
                6 -> { // ClientCutText
                    input.readFully(ByteArray(3))
                    val length = input.readInt()
                    if (length < 0) {
                        val payload = ByteArray(-length).also { input.readFully(it) }
                        val flags = java.nio.ByteBuffer.wrap(payload, 0, 4).int
                        when {
                            flags and VncClient.CLIP_CAPS != 0 -> clientCaps = true
                            flags and VncClient.CLIP_NOTIFY != 0 ->
                                sendExtended(out, VncClient.CLIP_REQUEST or VncClient.CLIP_TEXT, ByteArray(0))
                            flags and VncClient.CLIP_REQUEST != 0 -> sendExtended(
                                out, VncClient.CLIP_PROVIDE or VncClient.CLIP_TEXT,
                                deflateText(EXTENDED_TEXT.replace("\n", "\r\n")),
                            )
                            flags and VncClient.CLIP_PROVIDE != 0 -> {
                                val data = DataInputStream(
                                    java.util.zip.InflaterInputStream(payload.copyOfRange(4, payload.size).inputStream()),
                                )
                                val bytes = ByteArray(data.readInt()).also { data.readFully(it) }
                                clipboardTexts.add(String(bytes, Charsets.UTF_8).trimEnd('\u0000'))
                            }
                        }
                    } else {
                        val text = ByteArray(length)
                        input.readFully(text)
                        clipboardTexts.add(String(text, Charsets.ISO_8859_1))
                    }
                }
                251 -> { // SetDesktopSize
                    input.readUnsignedByte()
                    val w = input.readUnsignedShort()
                    val h = input.readUnsignedShort()
                    val screens = input.readUnsignedByte()
                    input.readUnsignedByte()
                    input.readFully(ByteArray(screens * 16))
                    desktopSizeRequests.add(w to h)
                    pendingSize = w to h
                    trace("desktop size request ${w}x$h")
                }
                else -> {
                    trace("unexpected message $type, closing")
                    return
                }
            }
        }
    }

    /** The client sent its extended clipboard capabilities. */
    @Volatile var clientCaps = false

    private fun sendExtended(out: OutputStream, flags: Int, data: ByteArray) {
        synchronized(out) {
            out.write(3)                     // ServerCutText
            out.write(byteArrayOf(0, 0, 0))  // padding
            out.write((-(4 + data.size)).toBigEndian())
            out.write(flags.toBigEndian())
            out.write(data)
            out.flush()
        }
    }

    private fun deflateText(text: String): ByteArray {
        val utf8 = (text + "\u0000").toByteArray(Charsets.UTF_8)
        val raw = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(raw).use { z ->
            z.write(utf8.size.toBigEndian())
            z.write(utf8)
        }
        return raw.toByteArray()
    }

    private fun sendUpdate(out: OutputStream) {
        if (resizable && sendResizableUpdate(out)) return
        if (copyRectAfterFirstFrame && framesSent++ == 1) {
            val (sx, sy, w, h, dx, dy) = copy.toList()
            out.write(0); out.write(0)
            out.write(1.toShortBigEndian())
            out.write(dx.toShortBigEndian()); out.write(dy.toShortBigEndian())
            out.write(w.toShortBigEndian()); out.write(h.toShortBigEndian())
            out.write(1.toBigEndian())                // CopyRect
            out.write(sx.toShortBigEndian()); out.write(sy.toShortBigEndian())
            out.flush()
            return
        }
        if (extendedClipboard && clientCaps && framesSent++ == 0) {
            sendExtended(out, VncClient.CLIP_NOTIFY or VncClient.CLIP_TEXT, ByteArray(0))
        }
        if (sendCutText) {
            val text = "clipboard".toByteArray()
            out.write(3)                     // ServerCutText
            out.write(byteArrayOf(0, 0, 0))  // padding
            out.write(text.size.toBigEndian())
            out.write(text)
        }
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

    /**
     * The Xvnc behaviour: announce the screen layout first, then follow
     * size requests. Returns false when the normal image should be sent.
     */
    private fun sendResizableUpdate(out: OutputStream): Boolean {
        val size = pendingSize
        val announce = !announcedSize || size != null
        if (size != null) {
            currentWidth = size.first
            currentHeight = size.second
            pendingSize = null
        }
        val resized = currentWidth != width || currentHeight != height
        if (!announce && !resized) return false

        out.write(0)          // FramebufferUpdate
        out.write(0)          // padding
        val rects = (if (announce) 1 else 0) + (if (resized) 1 else 0)
        out.write(rects.toShortBigEndian())
        if (announce) {
            // ExtendedDesktopSize: x is the reason (1 = this client asked),
            // y the status (0 = done), then the screen layout.
            out.write((if (announcedSize) 1 else 0).toShortBigEndian())
            out.write(0.toShortBigEndian())
            out.write(currentWidth.toShortBigEndian())
            out.write(currentHeight.toShortBigEndian())
            out.write((-308).toBigEndian())
            out.write(byteArrayOf(1, 0, 0, 0))
            out.write(7.toBigEndian())                // screen id
            out.write(0.toShortBigEndian())
            out.write(0.toShortBigEndian())
            out.write(currentWidth.toShortBigEndian())
            out.write(currentHeight.toShortBigEndian())
            out.write(0.toBigEndian())                // flags
            announcedSize = true
        }
        if (resized) {
            out.write(0.toShortBigEndian())
            out.write(0.toShortBigEndian())
            out.write(currentWidth.toShortBigEndian())
            out.write(currentHeight.toShortBigEndian())
            out.write(0.toBigEndian())                // Raw
            val bpp = (format.bitsPerPixel + 7) / 8
            val value = encode(RESIZED_COLOUR)
            val pixel = ByteArray(bpp) { b ->
                val shift = if (format.bigEndian) (bpp - 1 - b) * 8 else b * 8
                ((value shr shift) and 0xFF).toByte()
            }
            repeat(currentWidth * currentHeight) { out.write(pixel) }
        }
        out.flush()
        return true
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

    /** What the client should show after the CopyRect: the block, moved. */
    fun expectedAfterCopy(): IntArray {
        val before = expectedDecoded()
        val after = before.copyOf()
        val (sx, sy, w, h, dx, dy) = copy.toList()
        for (row in 0 until h) for (col in 0 until w) {
            after[(dy + row) * width + dx + col] = before[(sy + row) * width + sx + col]
        }
        return after
    }

    private operator fun <T> List<T>.component6(): T = this[5]

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
        if (hextileSubrects) return hextileSubrectBytes()
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

    /** Hextile with a background colour and coloured sub-rectangles per tile (RFC 6143 7.7.4). */
    private fun hextileSubrectBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun pixel(argb: Int) {
            val bpp = (format.bitsPerPixel + 7) / 8
            val value = encode(argb)
            for (b in 0 until bpp) {
                val shift = if (format.bigEndian) (bpp - 1 - b) * 8 else b * 8
                out.write((value shr shift) and 0xFF)
            }
        }
        var ty = 0
        while (ty < height) {
            val th = minOf(16, height - ty)
            var tx = 0
            while (tx < width) {
                val tw = minOf(16, width - tx)
                val background = source[ty * width + tx]
                val subrects = mutableListOf<IntArray>()
                for (by in 0 until th step 4) for (bx in 0 until tw step 4) {
                    val c = source[(ty + by) * width + (tx + bx)]
                    if (c != background) subrects += intArrayOf(c, bx, by, minOf(4, tw - bx), minOf(4, th - by))
                }
                if (subrects.isEmpty()) {
                    out.write(0x02) // background only: a solid tile
                    pixel(background)
                } else {
                    out.write(0x02 or 0x08 or 0x10)
                    pixel(background)
                    out.write(subrects.size)
                    for (s in subrects) {
                        pixel(s[0])
                        out.write((s[1] shl 4) or s[2])
                        out.write(((s[3] - 1) shl 4) or (s[4] - 1))
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

/** The colour of the 4x4 block a pixel is in; every other tile row is solid. */
private fun blockColour(x: Int, y: Int): Int {
    val tileRow = y / 16
    val r: Int
    val g: Int
    val b: Int
    if (tileRow % 2 == 1) {
        r = (x / 16) * 60 + 20; g = 200; b = 30
    } else {
        r = ((x / 4) * 37) and 0xFF; g = ((y / 4) * 53) and 0xFF; b = ((x / 4 + y / 4) % 2) * 0xFF
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
