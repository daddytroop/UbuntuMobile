package com.ubuntumobile.linux

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs commands inside the proot-managed Ubuntu container.
 *
 * Android's [ProcessBuilder] environment applies to the *host* process, and
 * proot's guest environment is set by the guest's own `env`/login shell. To
 * get a precise guest environment we launch proot through a small shell
 * wrapper that exports the variables and then `exec`s proot.
 */
class LinuxRuntime(private val paths: LinuxPaths) {

    /**
     * Base proot invocation.
     *
     * -r <rootfs>          : the chroot root
     * -0                   : fake root uid inside the guest (apt/flatpak need it)
     * -b /dev /proc /sys   : kernel filesystems the guest expects
     * -b <sdcard>          : shared storage, mapped to /sdcard and /storage/emulated/0
     * -w /root             : initial working directory
     *
     * NOTE: proot does not accept a bare "--" as an end-of-options marker;
     * the Termux build rejects it with "unknown option '--'".
     */
    internal fun baseArgs(): List<String> = listOf(
        "-r", paths.root.absolutePath,
        "-0",
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "/data",
        "-b", "/sdcard",
        "-b", paths.sdcardDir.absolutePath,
        "-w", "/root",
    )

    /** Environment passed to guest processes. */
    fun guestEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("USER", "root")
        put("LOGNAME", "root")
        put("SHELL", "/bin/bash")
        put("TERM", "xterm-256color")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("LANG", "en_US.UTF-8")
        put("LC_ALL", "en_US.UTF-8")
        put("DISPLAY", ":0")
        put("XDG_RUNTIME_DIR", "/tmp/runtime-root")
        put("TMPDIR", "/tmp")
        put("DEBIAN_FRONTEND", "noninteractive")
        putAll(extra)
    }

    /**
     * Environment for the *host* proot process.
     *
     * Termux's proot binary has the paths of its loader helper baked in
     * (`/data/data/com.termux/files/usr/libexec/proot/loader`). Those files
     * do not exist in our APK, so we point proot at our own copies via
     * PROOT_LOADER / PROOT_LOADER_32, otherwise every execve fails with
     * "the loader was not found or doesn't work".
     */
    private fun hostEnv(): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin",
        "HOME" to paths.homeDir.absolutePath,
        "TMPDIR" to paths.cacheDir.absolutePath,
        "LD_LIBRARY_PATH" to paths.libDir.absolutePath,
        "PROOT_LOADER" to File(paths.libDir, "loader").absolutePath,
        "PROOT_LOADER_32" to File(paths.libDir, "loader32").absolutePath,
        "PROOT_TMP_DIR" to paths.cacheDir.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_L2S_DUMMY" to "1",
    )

    /**
     * Build the argv for a guest command, wrapped in a shell script that sets
     * the guest environment first.
     */
    fun buildGuestCommand(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: String = "/root",
    ): List<String> {
        val script = writeWrapper(command, env, workingDir)
        return listOf("/system/bin/sh", script.absolutePath)
    }

    private fun writeWrapper(
        command: List<String>,
        env: Map<String, String>,
        workingDir: String,
    ): File {
        paths.cacheDir.mkdirs()
        val script = File(paths.cacheDir, "run-${System.nanoTime()}.sh")
        val exported = guestEnv(env).entries.joinToString(" ") { (k, v) ->
            "export ${shQuote(k)}=${shQuote(v)}"
        }
        val exec = command.joinToString(" ") { shQuote(it) }
        script.writeText(
            buildString {
                append("#!/system/bin/sh\n")
                append(exported).append('\n')
                append("cd ").append(shQuote(workingDir)).append(" 2>/dev/null || cd /\n")
                append("exec ").append(paths.proot.absolutePath).append(' ')
                append(baseArgs().joinToString(" ") { shQuote(it) }).append(' ')
                append(exec).append('\n')
            }
        )
        // The script is run as "sh script", so it only needs to be readable.
        // Do not touch the execute bit: the java.io.File permission helpers
        // can clobber the owner's read bit on Android.
        try {
            android.system.Os.chmod(script.absolutePath, 0x1A4)
        } catch (_: Exception) {
            script.setReadable(true, false)
        }
        return script
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Run a command in the guest and return its exit code plus captured output.
     *
     * @param timeoutSeconds wall-clock limit; the guest process is killed on expiry.
     */
    fun run(
        command: List<String>,
        timeoutSeconds: Long = 120,
        env: Map<String, String> = emptyMap(),
        workingDir: String = "/root",
    ): Result {
        if (!paths.isProotReady) {
            return Result(-1, "", "proot is not installed")
        }
        if (!paths.isInstalled) {
            return Result(-1, "", "Ubuntu is not installed yet")
        }

        return try {
            val pb = ProcessBuilder(buildGuestCommand(command, env, workingDir))
            pb.environment().apply {
                clear()
                putAll(hostEnv())
            }
            pb.redirectErrorStream(true)

            val proc = pb.start()
            val out = StringBuilder()
            val reader = proc.inputStream.bufferedReader()
            val t = Thread {
                try {
                    reader.forEachLine { out.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }
            t.isDaemon = true
            t.start()

            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Result(-1, out.toString(), "timed out after ${timeoutSeconds}s")
            }
            Result(proc.exitValue(), out.toString(), null)
        } catch (e: Exception) {
            Log.e(TAG, "run failed", e)
            Result(-1, "", e.message ?: "failed to start process")
        }
    }

    /** Convenience: run a shell command line inside the guest. */
    fun shell(script: String, timeoutSeconds: Long = 120): Result =
        run(listOf("/bin/bash", "-lc", script), timeoutSeconds)

    /** Result of a guest command. */
    data class Result(val exitCode: Int, val output: String, val error: String?) {
        val ok: Boolean get() = exitCode == 0
    }

    companion object {
        private const val TAG = "LinuxRuntime"

        /** Expand a stream with a progress callback (bytes copied). */
        fun copyWithProgress(input: InputStream, output: FileOutputStream, total: Long, onProgress: (Long) -> Unit) {
            val buf = ByteArray(128 * 1024)
            var copied = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                output.write(buf, 0, n)
                copied += n
                onProgress(copied)
            }
            output.flush()
        }
    }
}