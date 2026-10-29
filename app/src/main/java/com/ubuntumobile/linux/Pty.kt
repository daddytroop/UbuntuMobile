package com.ubuntumobile.linux

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An interactive terminal session running inside the Ubuntu container.
 *
 * Backed by a real POSIX PTY created in native code (bionic has no
 * forkpty/openpty), so full-screen programs, job control, colours and
 * readline all behave exactly as they would on a normal Linux box.
 */
class Pty(private val runtime: LinuxRuntime, private val paths: LinuxPaths) {

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var input: OutputStream? = null
    private var output: InputStream? = null
    private val running = AtomicBoolean(false)

    @Volatile
    var exitCode: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * Spawn an interactive login shell in the guest.
     *
     * @param onData invoked on a background thread with raw terminal bytes.
     */
    fun startShell(rows: Int = 24, cols: Int = 80, onData: (ByteArray, Int) -> Unit) {
        loadError?.let {
            throw IOException("The terminal helper is unavailable: $it")
        }
        val argv = runtime.buildGuestCommand(listOf("/bin/bash", "--login", "-i"))
        val env = runtime.guestEnv(mapOf("TERM" to "xterm-256color"))

        val jArgs = argv.toTypedArray()
        val jEnv = env.map { "${it.key}=${it.value}" }.toTypedArray()

        val pidOut = IntArray(1)
        val fd = nativeSpawn(jArgs, jEnv, paths.root.absolutePath, intArrayOf(rows, cols), pidOut)
        if (fd < 0) {
            Log.e(TAG, "nativeSpawn failed")
            throw IOException("failed to allocate pty")
        }
        masterFd = fd
        childPid = pidOut[0]
        running.set(true)

        // Wrap the raw pty master fd in Java streams.
        val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
        val inStream = FileInputStream(pfd.fileDescriptor)
        val outStream = FileOutputStream(pfd.fileDescriptor)
        output = inStream
        input = outStream

        Thread({
            val buf = ByteArray(8192)
            try {
                while (running.get()) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    onData(buf.copyOf(n), n)
                }
            } catch (e: IOException) {
                // PTY closed - normal on exit.
            } catch (e: Exception) {
                Log.e(TAG, "pty reader failed", e)
            } finally {
                // Reap the child so it does not linger as a zombie.
                if (childPid > 0) {
                    exitCode = nativeWaitPid(childPid)
                    running.set(false)
                }
            }
        }, "pty-reader").apply { isDaemon = true }.start()
    }

    /** Write user input to the terminal. */
    fun write(data: ByteArray, length: Int = data.size) {
        val out = input ?: return
        try {
            out.write(data, 0, length)
            out.flush()
        } catch (e: IOException) {
            Log.e(TAG, "pty write failed", e)
        }
    }

    fun write(text: String) = write(text.toByteArray())

    /** Resize the terminal (e.g. on rotation). */
    fun resize(rows: Int, cols: Int) {
        if (masterFd >= 0) nativeSetWinsize(masterFd, rows, cols)
    }

    /** Deliver a signal to the foreground process group (Ctrl-C / Ctrl-Z). */
    fun signal(sig: Int) {
        if (masterFd >= 0) nativeSignal(masterFd, sig)
    }

    /** Terminate the session. */
    fun close() {
        if (!running.getAndSet(false)) return
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        if (masterFd >= 0) {
            // The streams own the fd; closing them is enough.
            masterFd = -1
        }
    }

    companion object {
        private const val TAG = "Pty"

        /**
         * The native PTY helper. If it cannot be loaded we must not crash
         * the UI: the terminal is one feature among several, so we record the
         * failure and let the caller report it.
         */
        @Volatile
        var loadError: String? = null
            private set

        init {
            try {
                System.loadLibrary("linxpty")
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message ?: "liblinxpty.so could not be loaded"
                Log.e(TAG, "liblinxpty failed to load", e)
            }
        }

        @JvmStatic private external fun nativeSpawn(
            argv: Array<String>,
            envp: Array<String>,
            workdir: String,
            winsize: IntArray,
            pidOut: IntArray,
        ): Int

        @JvmStatic private external fun nativeWaitPid(pid: Int): Int
        @JvmStatic private external fun nativeTryWait(pid: Int): Int
        @JvmStatic private external fun nativeSetWinsize(fd: Int, rows: Int, cols: Int)
        @JvmStatic private external fun nativeSignal(fd: Int, sig: Int)
    }
}
