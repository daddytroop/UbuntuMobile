package com.ubuntumobile.display

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * A minimal RFB (VNC) client.
 *
 * The Android app talks directly to the RFB server that runs inside the
 * Ubuntu container. Because proot shares the host network namespace, the
 * server is reachable on 127.0.0.1 - no port forwarding or WebSocket proxy
 * is needed.
 *
 * Supported encodings: Raw (0), CopyRect (1) and Hextile (5). Hextile keeps
 * the amount of data on the wire small enough for a phone.
 *
 * Pixels are decoded according to the pixel format the server announced in
 * `ServerInit`. That is deliberate: a server may ignore `SetPixelFormat`, so
 * following the server's own description removes any ambiguity.
 */
class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901,
    /** Debugging aid: every byte the server sends is also written here. */
    private val capture: java.io.OutputStream? = null,
) {

    companion object {
        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_HEXTILE = 5

        private const val MSG_FRAMEBUFFER_UPDATE = 0
        private const val MSG_SET_COLOUR_MAP_ENTRIES = 1
        private const val MSG_BELL = 2
        private const val MSG_SERVER_CUT_TEXT = 3

        private const val MSG_KEY_EVENT = 4
        private const val MSG_POINTER_EVENT = 5

        /** The button bit of "wheel up" in a pointer event's mask. */
        const val BUTTON_WHEEL_UP = 8

        /** The button bit of "wheel down" in a pointer event's mask. */
        const val BUTTON_WHEEL_DOWN = 16

        /**
         * Read timeout while waiting for the next message; the loop simply
         * continues when it expires.
         */
        const val READ_TIMEOUT_MS = 1_000

        /**
         * Read timeout inside a message. A full frame is megabytes and the
         * server is slow under proot, so a pause here is not an error.
         */
        const val MESSAGE_TIMEOUT_MS = 20_000

        /** Re-ask for the framebuffer if the server has not answered by then. */
        const val REQUEST_RETRY_MS = 2_000

        /** Hextile subencoding flags. */
        const val HEXTILE_BACKGROUND = 0x02
        const val HEXTILE_FOREGROUND = 0x04
        const val HEXTILE_ANY_SUBRECTS = 0x08
        const val HEXTILE_SUBRECTS_COLOURED = 0x10

        /** Guard against a malformed ServerInit claiming an absurd size. */
        const val MAX_DIMENSION = 4096
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /**
     * The server's pixel format, as described by `ServerInit`.
     */
    data class PixelFormat(
        val bitsPerPixel: Int,
        val depth: Int,
        val bigEndian: Boolean,
        val trueColour: Boolean,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int,
        val redShift: Int,
        val greenShift: Int,
        val blueShift: Int,
    ) {
        val bytesPerPixel: Int get() = (bitsPerPixel + 7) / 8

        /** Convert a raw pixel value into an Android ARGB pixel. */
        fun toArgb(value: Int): Int {
            if (!trueColour) {
                // Greyscale or a colour map: approximate with the raw level.
                val g = if (redMax > 0) (value and redMax) * 255 / redMax else 0
                return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val r = ((value shr redShift) and redMax) * 255 / maxOf(redMax, 1)
            val g = ((value shr greenShift) and greenMax) * 255 / maxOf(greenMax, 1)
            val b = ((value shr blueShift) and blueMax) * 255 / maxOf(blueMax, 1)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** The pixel format the server announced. */
    var pixelFormat: PixelFormat = PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0)
        private set

    /** Current framebuffer size. */
    var width = 0
        private set
    var height = 0
        private set

    /** Name reported by the server (the X display name). */
    var desktopName: String = ""
        private set

    /**
     * The framebuffer as Android ARGB pixels.
     *
     * Values are 0xAARRGGBB so they can be handed straight to
     * `Bitmap.setPixels`.
     */
    var framebuffer: IntArray = IntArray(0)
        private set

    /** Called after every framebuffer update with the updated regions. */
    var onUpdate: ((List<Rect>) -> Unit)? = null

    /** A rectangle of the framebuffer that changed. */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Connect and perform the RFB handshake. */
    fun connect() {
        val s = Socket(host, port)
        s.tcpNoDelay = true
        // The handshake gets a generous timeout: the server runs under
        // proot, where everything is several times slower than native.
        s.soTimeout = MESSAGE_TIMEOUT_MS
        socket = s
        val raw = s.getInputStream().let { if (capture != null) TeeInputStream(it, capture) else it }
        input = DataInputStream(BufferedInputStream(raw, 64 * 1024))
        // Every message is written field by field and flushed once, so it
        // leaves as one packet instead of one per byte.
        output = java.io.BufferedOutputStream(s.getOutputStream(), 4096)

        // The protocol version is a fixed 12-byte string with no length prefix.
        val version = readAsciiLine(12)
        if (!version.startsWith("RFB ")) {
            throw IllegalStateException("not an RFB server: $version")
        }
        writeBytes("RFB 003.008\n")

        // Security handshake
        val count = input!!.readUnsignedByte()
        val types = ByteArray(count) { input!!.readUnsignedByte().toByte() }
        if (types.isEmpty()) throw IllegalStateException("no security types offered")
        if (!types.contains(1.toByte())) {
            throw IllegalStateException("server requires authentication: ${types.toList()}")
        }
        writeByte(1)

        val result = input!!.readInt()
        if (result != 0) throw IllegalStateException("security handshake failed: $result")

        // ClientInit: shared session
        writeByte(1)

        // ServerInit
        width = input!!.readUnsignedShort().coerceIn(1, MAX_DIMENSION)
        height = input!!.readUnsignedShort().coerceIn(1, MAX_DIMENSION)
        pixelFormat = readPixelFormat()
        val nameLength = input!!.readInt()
        val name = ByteArray(nameLength.coerceIn(0, 4096)) {
            input!!.readUnsignedByte().toByte()
        }
        desktopName = String(name, Charsets.UTF_8)

        framebuffer = IntArray(width * height)

        // Ask for the encodings we can decode, in preference order.
        setEncodings(listOf(ENCODING_HEXTILE, ENCODING_COPY_RECT, ENCODING_RAW))

        // Re-send the server's own pixel format. Some servers (notably
        // libvncserver, which TigerVNC uses) will not start sending frame
        // updates until the client has sent SetPixelFormat. Because we echo
        // back exactly what the server announced, the format cannot change
        // under us - the decoder always uses [pixelFormat].
        sendPixelFormat(pixelFormat)
    }

    /**
     * Read messages until the server goes away.
     *
     * The [onUpdate] callback is invoked on the calling thread after every
     * framebuffer update.
     */
    fun runLoop() {
        val stream = input ?: throw IllegalStateException("not connected")
        val s = socket ?: throw IllegalStateException("not connected")
        while (isConnected) {
            s.soTimeout = READ_TIMEOUT_MS
            val type = try {
                stream.readUnsignedByte()
            } catch (_: java.net.SocketTimeoutException) {
                // No traffic for a moment. Use it as a watchdog tick.
                watchdog()
                continue
            }
            // The rest of the message follows, but maybe not at once.
            s.soTimeout = MESSAGE_TIMEOUT_MS
            when (type) {
                MSG_FRAMEBUFFER_UPDATE -> {
                    handleFramebufferUpdate()
                    framesReceived++
                    // Keep exactly one request outstanding: the server sends
                    // damage updates while a request is pending. Sending a
                    // second request while one is still pending is a protocol
                    // violation and servers may ignore it.
                    requestIncrementalUpdate()
                }
                MSG_SET_COLOUR_MAP_ENTRIES -> {
                    // RFC 6143 7.6.2: padding (1), first colour (2), count (2).
                    skipFully(1)
                    stream.readUnsignedShort()
                    val count = stream.readUnsignedShort()
                    skipFully(count * 6L)
                }
                MSG_BELL -> { /* ignore */ }
                MSG_SERVER_CUT_TEXT -> {
                    // RFC 6143 7.6.4: padding (3), length (4), text. x11vnc
                    // sends one whenever the clipboard changes; forgetting
                    // the padding puts the client three bytes out of step.
                    skipFully(3)
                    val length = stream.readInt()
                    skipFully(length.toLong())
                }
                else -> throw IllegalStateException(
                    "unexpected server message: $type after ${recent.joinToString()}",
                )
            }
        }
    }

    /** The last few things decoded, so a loss of sync can say where it happened. */
    private val recent = ArrayDeque<String>()

    private fun remember(what: String) {
        if (recent.size >= 6) recent.removeFirst()
        recent.addLast(what)
    }

    /** True while a framebuffer update request is outstanding. */
    private var requestOutstanding = false
    private var lastRequestAt = 0L
    private var framesReceived = 0

    /**
     * Re-ask for the framebuffer if the server has not answered.
     *
     * Some servers only send an initial frame to a client that asks in a
     * particular way, or stay silent when the screen is unchanged. Rather
     * than giving up (or spamming requests, which is a protocol violation),
     * we replace the outstanding request with a fresh full one.
     */
    private fun watchdog() {
        if (!requestOutstanding) return
        // Once a frame has arrived, silence just means nothing changed: an
        // idle desktop must not be re-sent in full (megabytes) every time.
        if (framesReceived > 0) return
        if (System.currentTimeMillis() - lastRequestAt < REQUEST_RETRY_MS) return
        requestFullUpdate()
    }

    /** Request a full framebuffer refresh. */
    @Synchronized
    fun requestFullUpdate() {
        val out = output ?: return
        out.write(3) // FramebufferUpdateRequest
        out.write(0) // incremental = false
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.flush()
        requestOutstanding = true
        lastRequestAt = System.currentTimeMillis()
    }

    /**
     * Ask for the next update only (incremental).
     *
     * Servers push damage updates while an incremental request is pending,
     * so keeping one outstanding is what keeps the stream alive. A server
     * that has nothing to send simply waits.
     */
    @Synchronized
    fun requestIncrementalUpdate() {
        val out = output ?: return
        out.write(3) // FramebufferUpdateRequest
        out.write(1) // incremental = true
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.flush()
        requestOutstanding = true
        lastRequestAt = System.currentTimeMillis()
    }

    private fun handleFramebufferUpdate() {
        val stream = input!!
        stream.readUnsignedByte() // padding
        val rectCount = stream.readUnsignedShort()
        val changed = ArrayList<Rect>(rectCount)

        for (i in 0 until rectCount) {
            val x = stream.readUnsignedShort()
            val y = stream.readUnsignedShort()
            val w = stream.readUnsignedShort()
            val h = stream.readUnsignedShort()
            val encoding = stream.readInt()

            when (encoding) {
                ENCODING_RAW -> {
                    readRawPixels(x, y, w, h)
                    changed += Rect(x, y, w, h)
                }
                ENCODING_COPY_RECT -> {
                    val srcX = stream.readUnsignedShort()
                    val srcY = stream.readUnsignedShort()
                    copyRect(srcX, srcY, x, y, w, h)
                    changed += Rect(x, y, w, h)
                }
                ENCODING_HEXTILE -> {
                    readHextile(x, y, w, h)
                    changed += Rect(x, y, w, h)
                }
                else -> throw IllegalStateException(
                    "unsupported encoding: $encoding after ${recent.joinToString()}",
                )
            }
            remember("rect $x,$y ${w}x$h enc=$encoding")
        }

        if (changed.isNotEmpty()) onUpdate?.invoke(changed)
    }

    /** Read raw pixel data in the server's pixel format. */
    private fun readRawPixels(x: Int, y: Int, w: Int, h: Int) {
        val pf = pixelFormat
        val bpp = pf.bytesPerPixel
        val bytes = ByteArray(w * h * bpp)
        input!!.readFully(bytes)
        blit(bytes, x, y, w, h, bpp)
    }

    /** Hextile: 16x16 tiles, each raw or a set of sub-rectangles. */
    private fun readHextile(x: Int, y: Int, w: Int, h: Int) {
        val stream = input!!
        val bpp = pixelFormat.bytesPerPixel
        val bytes = ByteArray(16 * 16 * bpp)
        var tileY = y
        while (tileY < y + h) {
            val tileHeight = minOf(16, y + h - tileY)
            var tileX = x
            while (tileX < x + w) {
                val tileWidth = minOf(16, x + w - tileX)
                val subencoding = stream.readUnsignedByte()

                if (subencoding and 0x01 != 0) {
                    // Raw tile - the pixel data follows directly.
                    val n = tileWidth * tileHeight * bpp
                    stream.readFully(bytes, 0, n)
                    blit(bytes, tileX, tileY, tileWidth, tileHeight, bpp)
                } else {
                    // RFC 6143 section 7.7.4. Background and foreground
                    // carry over from the previous tile when not given.
                    if (subencoding and HEXTILE_BACKGROUND != 0) {
                        lastBackground = readPixel()
                    }
                    if (subencoding and HEXTILE_FOREGROUND != 0) {
                        lastForeground = readPixel()
                    }
                    // The tile starts out as background...
                    fillRect(tileX, tileY, tileWidth, tileHeight, lastBackground)
                    // ...and sub-rectangles are painted over it.
                    if (subencoding and HEXTILE_ANY_SUBRECTS != 0) {
                        val coloured = subencoding and HEXTILE_SUBRECTS_COLOURED != 0
                        // The count is a byte of its own.
                        val count = stream.readUnsignedByte()
                        repeat(count) {
                            val color = if (coloured) readPixel() else lastForeground
                            // x and y share a byte, as do width-1 and height-1.
                            val xy = stream.readUnsignedByte()
                            val wh = stream.readUnsignedByte()
                            val subX = xy shr 4
                            val subY = xy and 0x0F
                            val subW = (wh shr 4) + 1
                            val subH = (wh and 0x0F) + 1
                            fillRect(
                                tileX + subX, tileY + subY,
                                minOf(subW, tileWidth - subX),
                                minOf(subH, tileHeight - subY),
                                color,
                            )
                        }
                    }
                }
                tileX += 16
            }
            tileY += 16
        }
    }

    private var lastBackground = 0
    private var lastForeground = 0xFFFFFFFF.toInt()

    /**
     * Read the server's pixel format.
     *
     * The RFB `PIXEL_FORMAT` is 16 bytes and carries NO length prefix (the
     * 4-byte length only appears in the `SetPixelFormat` message, which we
     * do not send). Its fields are also not all 32-bit: bits-per-pixel,
     * depth and the flags are single bytes, the colour maxima are 16-bit
     * and the shifts are single bytes.
     */
    private fun readPixelFormat(): PixelFormat {
        val s = input!!
        val bpp = s.readUnsignedByte()
        val depth = s.readUnsignedByte()
        val bigEndian = s.readUnsignedByte() != 0
        val trueColour = s.readUnsignedByte() != 0
        val redMax = s.readUnsignedShort()
        val greenMax = s.readUnsignedShort()
        val blueMax = s.readUnsignedShort()
        val redShift = s.readUnsignedByte()
        val greenShift = s.readUnsignedByte()
        val blueShift = s.readUnsignedByte()
        skipFully(3) // padding
        return PixelFormat(
            bitsPerPixel = bpp,
            depth = depth,
            bigEndian = bigEndian,
            trueColour = trueColour,
            redMax = redMax,
            greenMax = greenMax,
            blueMax = blueMax,
            redShift = redShift,
            greenShift = greenShift,
            blueShift = blueShift,
        )
    }

    private fun setEncodings(encodings: List<Int>) {
        val out = output!!
        out.write(2) // SetEncodings
        out.write(0) // padding
        writeShort(encodings.size)
        encodings.forEach { writeInt(it) }
        out.flush()
    }

    /** Send SetPixelFormat describing [pf]. */
    private fun sendPixelFormat(pf: PixelFormat) {
        val out = output!!
        out.write(0)                    // SetPixelFormat
        out.write(byteArrayOf(0, 0, 0)) // padding
        out.write(pf.bitsPerPixel)
        out.write(pf.depth)
        out.write(if (pf.bigEndian) 1 else 0)
        out.write(if (pf.trueColour) 1 else 0)
        writeShort(pf.redMax)
        writeShort(pf.greenMax)
        writeShort(pf.blueMax)
        out.write(pf.redShift)
        out.write(pf.greenShift)
        out.write(pf.blueShift)
        out.write(byteArrayOf(0, 0, 0)) // padding
        out.flush()
    }

    /** Send a pointer (mouse / touch) event. */
    @Synchronized
    fun sendPointer(x: Int, y: Int, mask: Int) {
        val out = output ?: return
        out.write(MSG_POINTER_EVENT)
        out.write(mask)
        writeShort(x.coerceIn(0, 65535))
        writeShort(y.coerceIn(0, 65535))
        out.flush()
    }

    /**
     * Send wheel events.
     *
     * The RFB protocol has no wheel message: a notch is a pointer event
     * with the button bit of button 4 (wheel up) or button 5 (wheel down)
     * set in the mask.
     */
    @Synchronized
    fun sendScroll(x: Int, y: Int, steps: Int) {
        val out = output ?: return
        var remaining = steps
        while (remaining != 0) {
            val mask = if (remaining > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
            out.write(MSG_POINTER_EVENT)
            out.write(mask)
            writeShort(x.coerceIn(0, 65535))
            writeShort(y.coerceIn(0, 65535))
            out.flush()
            if (remaining > 0) remaining-- else remaining++
        }
    }

    /** Send a key event. */
    @Synchronized
    fun sendKey(keysym: Int, down: Boolean) {
        val out = output ?: return
        // RFC 6143 7.5.4: type, down-flag, two bytes of padding, keysym.
        // With one padding byte the server reads every key press a byte
        // short and drops the connection.
        out.write(MSG_KEY_EVENT)
        out.write(if (down) 1 else 0)
        out.write(0) // padding
        out.write(0)
        writeInt(keysym)
        out.flush()
    }



    /** Close the connection. */
    @Synchronized
    fun close() {
        try { output?.flush() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    // ---------------------------------------------------------------- pixels

    /** Convert a raw pixel block into ARGB pixels in the framebuffer. */
    private fun blit(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int, bpp: Int) {
        val fb = framebuffer
        val pf = pixelFormat
        var src = 0
        for (row in 0 until h) {
            val py = y + row
            if (py < 0 || py >= height) { src += w * bpp; continue }
            var dst = py * width + x
            for (col in 0 until w) {
                val px = x + col
                if (px in 0 until width) {
                    var value = 0
                    if (pf.bigEndian) {
                        for (i in 0 until bpp) {
                            value = (value shl 8) or (bytes[src + i].toInt() and 0xFF)
                        }
                    } else {
                        for (i in bpp - 1 downTo 0) {
                            value = (value shl 8) or (bytes[src + i].toInt() and 0xFF)
                        }
                    }
                    fb[dst] = pf.toArgb(value)
                }
                src += bpp
                dst++
            }
        }
    }

    /** Read a single pixel value in the server's pixel format. */
    private fun readPixel(): Int {
        val pf = pixelFormat
        val bpp = pf.bytesPerPixel
        val bytes = ByteArray(bpp)
        input!!.readFully(bytes)
        var value = 0
        if (pf.bigEndian) {
            for (i in 0 until bpp) value = (value shl 8) or (bytes[i].toInt() and 0xFF)
        } else {
            for (i in bpp - 1 downTo 0) value = (value shl 8) or (bytes[i].toInt() and 0xFF)
        }
        return pf.toArgb(value)
    }

    private fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        if (w <= 0 || h <= 0) return
        val fb = framebuffer
        for (row in 0 until h) {
            val py = y + row
            if (py < 0 || py >= height) continue
            var dst = py * width + x
            for (col in 0 until w) {
                val px = x + col
                if (px in 0 until width) fb[dst] = argb
                dst++
            }
        }
    }

    private fun copyRect(srcX: Int, srcY: Int, dstX: Int, dstY: Int, w: Int, h: Int) {
        val fb = framebuffer
        val tmp = IntArray(w * h)
        for (row in 0 until h) {
            val sy = srcY + row
            if (sy < 0 || sy >= height) continue
            for (col in 0 until w) {
                val sx = srcX + col
                if (sx in 0 until width) tmp[row * w + col] = fb[sy * width + sx]
            }
        }
        for (row in 0 until h) {
            val dy = dstY + row
            if (dy < 0 || dy >= height) continue
            for (col in 0 until w) {
                val dx = dstX + col
                if (dx in 0 until width) fb[dy * width + dx] = tmp[row * w + col]
            }
        }
    }

    private fun readAsciiLine(max: Int): String {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = input!!.read()
            if (c == -1 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun writeBytes(s: String) {
        output!!.write(s.toByteArray(Charsets.US_ASCII))
        output!!.flush()
    }

    private fun writeByte(b: Int) {
        output!!.write(b)
        output!!.flush()
    }

    /** Write a big-endian 16-bit value (RFB uses network byte order). */
    private fun writeShort(value: Int) {
        output!!.write((value shr 8) and 0xFF)
        output!!.write(value and 0xFF)
    }

    /** Write a big-endian 32-bit value. */
    private fun writeInt(value: Int) {
        output!!.write((value shr 24) and 0xFF)
        output!!.write((value shr 16) and 0xFF)
        output!!.write((value shr 8) and 0xFF)
        output!!.write(value and 0xFF)
    }

    private fun skipFully(count: Long) {
        if (count <= 0) return
        var remaining = count
        while (remaining > 0) {
            val skipped = input!!.skip(remaining)
            if (skipped <= 0) {
                if (input!!.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}

/** Copies everything read from [source] to [sink]. */
private class TeeInputStream(
    private val source: java.io.InputStream,
    private val sink: java.io.OutputStream,
) : java.io.InputStream() {
    override fun read(): Int = source.read().also { if (it >= 0) sink.write(it) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        source.read(b, off, len).also { if (it > 0) sink.write(b, off, it) }

    override fun close() {
        source.close()
        sink.flush()
    }
}
