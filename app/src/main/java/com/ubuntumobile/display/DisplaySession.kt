package com.ubuntumobile.display

import android.graphics.Bitmap
import android.util.Log

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
    }

    /** Current frame size. */
    @Volatile
    var width: Int = 0
        private set

    @Volatile
    var height: Int = 0
        private set

    /** The framebuffer as an Android bitmap, updated in place. */
    @Volatile
    var bitmap: Bitmap? = null
        private set

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    /** Called (on the reader thread) whenever a new frame is available. */
    var onFrame: (() -> Unit)? = null

    private var client: VncClient? = null
    private var reader: Thread? = null

    @Synchronized
    fun start(): Boolean {
        if (connected) return true
        return try {
            val c = VncClient(host, port)
            c.connect()
            client = c
            width = c.width
            height = c.height
            bitmap = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
            error = null

            c.onUpdate = { rects ->
                // Copy the changed regions into the bitmap.
                val bmp = bitmap
                if (bmp != null) {
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
                    onFrame?.invoke()
                }
            }

            val t = Thread({
                try {
                    c.runLoop()
                } catch (e: Exception) {
                    Log.w(TAG, "display reader stopped", e)
                }
            }, "vnc-reader")
            t.isDaemon = true
            reader = t
            t.start()

            // Ask for the first frame. After that the client keeps exactly
            // one request outstanding by re-requesting after each update.
            c.requestFullUpdate()

            connected = true
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
        try { client?.close() } catch (_: Exception) {}
        client = null
        reader = null
    }

    /** Send a pointer event (mouse move / button). */
    fun pointer(x: Int, y: Int, mask: Int) {
        client?.sendPointer(x, y, mask)
    }

    /** Send a key event. */
    fun key(keysym: Int, down: Boolean) {
        client?.sendKey(keysym, down)
    }

    /** Ask the server for a complete repaint. */
    fun refresh() {
        client?.requestFullUpdate()
    }
}
