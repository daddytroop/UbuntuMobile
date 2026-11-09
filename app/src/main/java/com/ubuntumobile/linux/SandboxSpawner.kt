package com.ubuntumobile.linux

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Starts Flatpak apps in a proot of their own, on behalf of linx-bwrap.
 *
 * Flatpak builds each app's file system with bwrap, which proot cannot run.
 * linx-bwrap (inside the container) works out the same file system - a root
 * directory and a list of bind mounts - and could run it under a second
 * proot nested in the container's, but every system call then passes
 * through two tracers and the app runs about thirteen times slower. So it
 * asks here instead, over an abstract unix socket, and the app starts a
 * fresh proot next to the container's own: the app runs as fast as
 * anything else.
 *
 * The protocol is one JSON line from the client:
 *
 *     {"root": dir, "binds": [[src, dest], ...], "cwd": dir,
 *      "env": {name: value}, "argv": [program, args...]}
 *
 * with container paths throughout, then frames back: 'O' + length + bytes
 * of output, and finally 'X' + the exit code. The client may send 'K' +
 * a signal number to stop the app.
 */
object SandboxSpawner {
    private const val TAG = "SandboxSpawner"

    @Volatile private var started = false

    /** The socket's name, also known to linx-bwrap (it reads it from a file). */
    fun socketName(packageName: String) = "$packageName.sandbox"

    /** Listen, if not yet, and tell the container where (again after an install). */
    @Synchronized
    fun start(packageName: String, paths: LinuxPaths, runtime: LinuxRuntime) {
        // linx-bwrap finds the socket by this file.
        if (paths.isInstalled) {
            try {
                File(paths.root, "etc/linx").mkdirs()
                File(paths.root, "etc/linx/sandbox-socket").writeText(socketName(packageName) + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "cannot write the socket name", e)
            }
        }
        if (started) return
        val server = try {
            LocalServerSocket(socketName(packageName))
        } catch (e: Exception) {
            Log.w(TAG, "cannot listen", e)
            return
        }
        started = true
        Thread({
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed", e)
                    continue
                }
                Thread({ serve(client, paths, runtime) }, "sandbox-client").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "sandbox-spawner").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Where a container path is on the phone: the container's own bind
     * mounts (see [LinuxRuntime.baseArgsFor]) or the root file system.
     */
    internal fun hostPath(path: String, root: String, sharedTmp: String, shm: String, sdcard: String): String {
        fun under(prefix: String) = path == prefix || path.startsWith("$prefix/")
        return when {
            under("/tmp") -> sharedTmp + path.removePrefix("/tmp")
            under("/dev/shm") -> shm + path.removePrefix("/dev/shm")
            under("/dev") || under("/proc") || under("/sys") || under("/data") ||
                under("/sdcard") || under(sdcard) -> path
            else -> root + path
        }
    }

    /** The proot command line for a request. */
    internal fun prootArgs(
        request: JSONObject,
        root: String,
        sharedTmp: String,
        shm: String,
        sdcard: String,
    ): List<String> {
        fun host(p: String) = hostPath(p, root, sharedTmp, shm, sdcard)
        val args = mutableListOf(
            "-r", host(request.getString("root")),
            "-0", "--link2symlink", "--sysvipc",
            // proot fakes the hard links Flatpak's files are made of with
            // symlinks to the phone's own paths, under /data.
            "-b", "/data",
        )
        val binds = request.optJSONArray("binds")
        if (binds != null) {
            for (i in 0 until binds.length()) {
                val pair = binds.getJSONArray(i)
                args += listOf("-b", "${host(pair.getString(0))}:${pair.getString(1)}")
            }
        }
        args += listOf("-w", request.optString("cwd", "/").ifEmpty { "/" })
        val argv = request.getJSONArray("argv")
        for (i in 0 until argv.length()) args += argv.getString(i)
        return args
    }

    private fun serve(client: LocalSocket, paths: LinuxPaths, runtime: LinuxRuntime) {
        var process: Process? = null
        try {
            val input = DataInputStream(client.inputStream)
            val output = DataOutputStream(client.outputStream)
            val line = readLine(input) ?: return
            val request = JSONObject(line)
            val args = prootArgs(
                request,
                paths.root.absolutePath,
                paths.sharedTmpDir.absolutePath,
                paths.shmDir.absolutePath,
                paths.sdcardDir.absolutePath,
            )
            val pb = ProcessBuilder(listOf(paths.proot.absolutePath) + args)
            pb.environment().apply {
                clear()
                val env = request.optJSONObject("env")
                env?.keys()?.forEach { put(it, env.getString(it)) }
                // proot's own settings, and the libraries it needs.
                putAll(runtime.hostEnv().filterKeys { it.startsWith("PROOT_") || it == "LD_LIBRARY_PATH" })
            }
            pb.redirectErrorStream(true)
            pb.redirectInput(File("/dev/null"))
            val proc = pb.start()
            process = proc

            // Stop requests from the client; it going away stops the app too.
            Thread({
                try {
                    while (true) {
                        val type = input.readByte().toInt()
                        val value = input.readInt()
                        if (type == 'K'.code) {
                            if (value == 9) proc.destroyForcibly() else proc.destroy()
                        }
                    }
                } catch (_: Exception) {
                    if (proc.isAlive) proc.destroy()
                }
            }, "sandbox-control").apply { isDaemon = true; start() }

            val buf = ByteArray(16 * 1024)
            val stream = proc.inputStream
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                synchronized(output) {
                    output.writeByte('O'.code)
                    output.writeInt(n)
                    output.write(buf, 0, n)
                    output.flush()
                }
            }
            val code = proc.waitFor()
            synchronized(output) {
                output.writeByte('X'.code)
                output.writeInt(code)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "sandbox request failed", e)
            process?.destroy()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: DataInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() > 0) bytes.toString("UTF-8") else null
            if (b == '\n'.code) return bytes.toString("UTF-8")
            bytes.write(b)
            if (bytes.size() > 4 * 1024 * 1024) return null
        }
    }
}
