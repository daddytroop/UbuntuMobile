package com.ubuntumobile.display

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Owns the connection to the in-container display and keeps a [Bitmap] that
 * mirrors the X framebuffer.
 *
 * The RFB connection is made from a background thread; frames are copied
 * into the bitmap under a lock so the UI thread can blit them safely.
 */
class DisplaySession(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901,
) {

    companion object {
        private const val TAG = "DisplaySession"

        /** Connection attempts, a second apart, before giving up. */
        const val MAX_ATTEMPTS = 120
    }

    /** Current frame size. */
    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    // These three are Compose state, so the screen recomposes when they
    // change. Plain fields were only re-read when something else happened
    // to recompose, and the screen kept showing "not connected" over a
    // live connection. Snapshot state may be written from any thread.

    /** The framebuffer as an Android bitmap, updated in place. */
    var bitmap: Bitmap? by mutableStateOf(null)
        private set

    var connected: Boolean by mutableStateOf(false)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** True while (re)connecting; [attempt] counts the tries so far. */
    var connecting: Boolean by mutableStateOf(true)
        private set

    var attempt: Int by mutableIntStateOf(0)
        private set

    /**
     * Counts the frames received. Anything that draws [bitmap] reads it,
     * so it is redrawn when new pixels arrive - the phone's screen and an
     * external monitor alike.
     */
    var frames: Int by mutableIntStateOf(0)
        private set

    /** Where the X pointer is, for input that moves it relatively (a trackpad). */
    @Volatile
    var pointerX = 0
        private set

    @Volatile
    var pointerY = 0
        private set

    @Volatile
    private var retryRequested = false

    /** Debugging aid: when set, each connection's byte stream is saved next to it, timestamped. */
    var captureFile: java.io.File? = null

    /** Called (on the reader thread) when Linux puts text on its clipboard. */
    var onClipboard: ((String) -> Unit)? = null

    /** Called (on the main thread) after a tap on the phone's screen has clicked in Linux. */
    var onTapped: (() -> Unit)? = null

    /** Called (on the reader thread) whenever a new frame is available. */
    var onFrame: (() -> Unit)? = null

    @Volatile
    private var client: VncClient? = null
    private var reader: Thread? = null

    /**
     * Outgoing messages (input, update requests) are written from this one
     * thread. Android throws NetworkOnMainThreadException for any socket
     * write on the UI thread, and that is where touch and key events come
     * from. A single thread also keeps the events in order.
     */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vnc-sender").apply { isDaemon = true }
    }

    private fun send(block: (VncClient) -> Unit) {
        val c = client ?: return
        sender.execute {
            try {
                block(c)
            } catch (e: Exception) {
                Log.w(TAG, "could not send to the display", e)
            }
        }
    }

    /** Connect to the server. This blocks on the network: never call it on the main thread. */
    @Synchronized
    fun start(): Boolean {
        if (connected) return true
        return try {
            requested = null
            val c = VncClient(host, port, captureFile?.let { java.io.FileOutputStream(java.io.File(it.parentFile, "${it.nameWithoutExtension}-${System.currentTimeMillis()}.bin")) })
            c.connect()
            client = c
            width = c.width
            height = c.height
            pointerX = c.width / 2
            pointerY = c.height / 2
            bitmap = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
            // Nothing drawn in it yet: the screen waits for the first frame.
            frames = 0
            error = null

            c.onUpdate = { rects ->
                // Copy the changed regions into the bitmap.
                val bmp = bitmap
                if (bmp != null && bmp.width == c.width && bmp.height == c.height) {
                    val fb = c.framebuffer
                    synchronized(bmp) {
                        for (r in rects) {
                            val count = r.width * r.height
                            if (count <= 0) continue
                            val start = r.y * c.width + r.x
                            if (start < 0 || start + count > fb.size) continue
                            bmp.setPixels(fb, start, c.width, r.x, r.y, r.width, r.height)
                        }
                    }
                    frames++
                    onFrame?.invoke()
                }
                // The first update says whether the server can resize.
                applyWantedSize()
            }
            c.onClipboard = { text -> onClipboard?.invoke(text) }
            c.onResize = { w, h ->
                // A new bitmap rather than a resized one: the UI thread may
                // be drawing the old one right now.
                width = w
                height = h
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Log.i(TAG, "the display is now ${w}x$h")
            }

            val t = Thread({
                try {
                    c.runLoop()
                } catch (e: Exception) {
                    Log.w(TAG, "display reader stopped", e)
                    error = e.message ?: "the display connection was lost"
                }
                // Whatever the reason, the stream is gone: say so, so the
                // screen can reconnect instead of showing a frozen frame.
                if (client === c) {
                    connected = false
                    onFrame?.invoke()
                }
            }, "vnc-reader")
            t.isDaemon = true
            reader = t
            t.start()

            // Ask for the first frame. After that the client keeps exactly
            // one request outstanding by re-requesting after each update.
            c.requestFullUpdate()

            connected = true
            Log.i(TAG, "connected to the display (${c.width}x${c.height})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not connect to the display", e)
            error = e.message ?: "could not connect"
            connected = false
            false
        }
    }

    @Synchronized
    fun stop() {
        connected = false
        val c = client
        sender.execute { try { c?.close() } catch (_: Exception) {} }
        client = null
        reader = null
    }

    /**
     * Connect, and connect again whenever the stream drops (the server
     * restarted, the phone was asleep). Runs until it is cancelled.
     *
     * The X server needs a while before it accepts connections, and on a
     * phone that can be long - the desktop has to start as well - so a
     * refused connection is retried for a couple of minutes before giving
     * up until [retry].
     */
    suspend fun keepConnected() {
        var gaveUp = false
        while (true) {
            if (!connected && (!gaveUp || retryRequested)) {
                retryRequested = false
                // Whatever is left of the last connection goes first.
                withContext(Dispatchers.IO) { stop() }
                connecting = true
                attempt = 0
                while (!connected && attempt < MAX_ATTEMPTS) {
                    // Network I/O, which Android refuses on the main thread.
                    if (withContext(Dispatchers.IO) { start() }) break
                    attempt++
                    delay(1000)
                }
                connecting = false
                gaveUp = !connected
                if (connected) refresh()
            }
            delay(1000)
        }
    }

    /** Drop the connection and make a new one (or try again after giving up). */
    fun retry() {
        retryRequested = true
        if (connected) stop()
    }

    /** Send a pointer event (mouse move / button). */
    fun pointer(x: Int, y: Int, mask: Int) {
        pointerX = x
        pointerY = y
        send { it.sendPointer(x, y, mask) }
    }

    /**
     * Move the pointer by an offset, as a trackpad or a captured mouse
     * does, keeping it on the screen.
     */
    fun movePointer(dx: Int, dy: Int, mask: Int) {
        val x = (pointerX + dx).coerceIn(0, maxOf(width - 1, 0))
        val y = (pointerY + dy).coerceIn(0, maxOf(height - 1, 0))
        pointer(x, y, mask)
    }

    /**
     * Scroll the wheel at a position.
     *
     * A positive number of steps scrolls up, a negative one down.
     */
    fun scroll(x: Int, y: Int, steps: Int) {
        if (steps == 0) return
        send { it.sendScroll(x, y, steps) }
    }

    /** Send a key event. */
    fun key(keysym: Int, down: Boolean) {
        send { it.sendKey(keysym, down) }
    }

    /**
     * Type text, one key press per character.
     *
     * Soft keyboards hand the app committed text rather than key events,
     * so this is how the on-screen keyboard reaches X. Latin-1 characters
     * are their own keysyms; everything else uses the Unicode keysym range.
     */
    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val keysym = when {
                cp == '\n'.code -> 0xFF0D
                cp == '\t'.code -> 0xFF09
                cp in 0x20..0xFF -> cp
                else -> 0x01000000 or cp
            }
            send {
                it.sendKey(keysym, true)
                it.sendKey(keysym, false)
            }
        }
    }

    /** True when the server can change size on request (Xvnc can, x11vnc cannot). */
    val canResize: Boolean get() = client?.canResize == true

    /**
     * Ask the X server to change the size of the screen, for instance after
     * the phone rotated. The desktop rearranges itself for the new size.
     */
    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        wanted = w to h
        applyWantedSize()
    }

    /** The size the screen should have, and the last size asked for. */
    @Volatile
    private var wanted: Pair<Int, Int>? = null

    @Volatile
    private var requested: Pair<Int, Int>? = null

    private fun applyWantedSize() {
        val want = wanted ?: return
        val c = client ?: return
        // Until the server's first update it is unknown whether it can.
        if (!c.canResize) return
        // Asking again for a size the server refused would loop.
        if (want == (width to height) || want == requested) return
        requested = want
        send { it.requestDesktopSize(want.first, want.second) }
    }

    /** Put text on Linux's clipboard. */
    fun sendClipboard(text: String) {
        send { it.sendClipboard(text) }
    }

    /** Ask the server for a complete repaint. */
    fun refresh() {
        send { it.requestFullUpdate() }
    }
}
