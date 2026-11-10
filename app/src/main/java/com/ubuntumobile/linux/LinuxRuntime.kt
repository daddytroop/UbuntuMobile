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
     * --link2symlink       : emulate hard links (Android forbids them)
     * --sysvipc            : emulate System V shared memory (MIT-SHM)
     * -b /dev /proc /sys   : kernel filesystems the guest expects
     * -b <sdcard>          : shared storage, mapped to /sdcard and /storage/emulated/0
     * -w /root             : initial working directory
     *
     * NOTE: proot does not accept a bare "--" as an end-of-options marker;
     * the Termux build rejects it with "unknown option '--'".
     */
    internal fun baseArgs(): List<String> =
        baseArgsFor(
            paths.root.absolutePath, paths.sdcardDir.absolutePath,
            paths.sharedTmpDir.absolutePath, paths.shmDir.absolutePath,
        )


    /** The locale linx-locale.sh made ready (/etc/linx/locale), or C.UTF-8. */
    private fun phoneLocale(): String = try {
        java.io.File(paths.root, "etc/linx/locale").readText().trim()
            .takeIf { it.matches(Regex("[a-z]{2,3}(_[A-Z]{2})?\\.UTF-8")) } ?: "C.UTF-8"
    } catch (_: Exception) {
        "C.UTF-8"
    }

    /** Environment passed to guest processes. */
    fun guestEnv(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("USER", "root")
        put("LOGNAME", "root")
        put("SHELL", "/bin/bash")
        put("TERM", "xterm-256color")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        // C.UTF-8 is built into glibc, so it works whether or not the
        // locale archive has been compiled. en_US.UTF-8 would print a
        // "setlocale: cannot change locale" warning on every shell.
        // The phone's language once linx-locale.sh has compiled it.
        val locale = phoneLocale()
        put("LANG", locale)
        put("LC_ALL", locale)
        if (locale != "C.UTF-8") put("LANGUAGE", locale.substringBefore('_') + ":en")
        // The desktop's X server (display-start.sh, started as :99 by the
        // app), so whatever runs in the guest - the scripts restarting the
        // panel, a program started from the console - finds it.
        put("DISPLAY", ":99")
        put("XDG_RUNTIME_DIR", "/tmp/runtime-root")
        put("TMPDIR", "/tmp")
        put("DEBIAN_FRONTEND", "noninteractive")
        // The rootfs has no /etc/localtime, so without this every clock in
        // the guest shows UTC. The image ships tzdata, so the phone's own
        // zone name resolves.
        put("TZ", java.util.TimeZone.getDefault().id)
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
     *
     * This is the environment of the *process* that starts proot; the guest
     * environment is set by the wrapper script, which exports it before
     * exec'ing proot.
     */
    internal fun hostEnv(): Map<String, String> = mapOf(
        "PATH" to "/system/bin:/system/xbin",
        "HOME" to paths.homeDir.absolutePath,
        "TMPDIR" to paths.cacheDir.absolutePath,
        "LD_LIBRARY_PATH" to paths.libDir.absolutePath,
        "PROOT_LOADER" to File(paths.libDir, "loader").absolutePath,
        "PROOT_LOADER_32" to File(paths.libDir, "loader32").absolutePath,
        "PROOT_TMP_DIR" to paths.cacheDir.absolutePath,
        // PROOT_NO_SECCOMP is deliberately NOT set. With seccomp, proot only
        // stops a process for the system calls it has to rewrite; without
        // it every system call stops twice. Measured on the emulator, that
        // made starting programs 2.7 times and walking files 3.8 times
        // slower - the whole desktop with them.
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
            paths.appendLog("guest command failed: ${e.message}")
            Result(-1, "", e.message ?: "failed to start process")
        }
    }

    /** Convenience: run a shell command line inside the guest. */
    fun shell(script: String, timeoutSeconds: Long = 120): Result =
        run(listOf("/bin/bash", "-lc", script), timeoutSeconds)

    /**
     * Start a command in the guest and leave it running.
     *
     * proot registers `atexit(kill_all_tracees)`, so every process it
     * started is killed when proot exits. A background job therefore
     * cannot outlive the shell that launched it - which is why the X
     * server needs a proot process of its own that stays alive for as
     * long as the display is wanted.
     *
     * The returned [Process] must be kept alive and destroyed when the
     * guest program is no longer wanted.
     */
    fun startPersistent(command: List<String>, env: Map<String, String> = emptyMap()): Process? {
        if (!paths.isProotReady) {
            paths.appendLog("proot is not installed")
            return null
        }
        if (!paths.isInstalled) {
            paths.appendLog("Ubuntu is not installed yet")
            return null
        }
        return try {
            val pb = ProcessBuilder(buildGuestCommand(command, env))
            pb.environment().apply {
                clear()
                putAll(hostEnv())
            }
            pb.redirectErrorStream(true)
            // Drain the output so the process never blocks on a full pipe.
            val proc = pb.start()
            Thread({
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        // The guest's own log lines are the only clue about
                        // what happens inside the container, so keep them.
                        paths.appendLog("guest: $line")
                    }
                } catch (_: Exception) {
                }
            }, "guest-output").apply { isDaemon = true }.start()
            proc
        } catch (e: Exception) {
            Log.e(TAG, "could not start a persistent guest command", e)
            paths.appendLog("could not start a persistent guest command: ${e.message}")
            null
        }
    }

    /**
     * Start a guest command whose standard input and output the caller
     * uses as data - a backup streamed out of tar, a restore streamed in.
     * Its error output goes to the app's log. Null if it cannot start.
     */
    fun startPiped(command: List<String>): Process? {
        if (!paths.isProotReady || !paths.isInstalled) return null
        return try {
            val pb = ProcessBuilder(buildGuestCommand(command))
            pb.environment().apply {
                clear()
                putAll(hostEnv())
            }
            val proc = pb.start()
            Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { paths.appendLog("guest: $it") }
                } catch (_: Exception) {
                }
            }, "guest-errors").apply { isDaemon = true }.start()
            proc
        } catch (e: Exception) {
            Log.e(TAG, "could not start a piped guest command", e)
            null
        }
    }

    /**
     * Copy the container's display logs into the on-device log.
     *
     * The display server and the XFCE components write their output into
     * `/tmp` inside the container, which is where the app can read it from.
     * When the desktop does not come up on the phone, this is the only
     * place that says why.
     */
    fun dumpDisplayDiagnostics() {
        val result = shell(
            "for f in /tmp/linx-display.log /tmp/linx-session.log /tmp/linx-xtigervnc.log " +
                "/tmp/linx-xfwm4.log /tmp/linx-panel.log /tmp/linx-desktop.log " +
                "/tmp/linx-thunar.log; do " +
                "  [ -f \"\$f\" ] && echo \"--- \$f ---\" && tail -12 \"\$f\"; " +
                "done; echo '--- processes ---'; " +
                "ps ax 2>/dev/null | grep -E 'Xvnc|Xtigervnc|xfwm4|xfce4-panel|xfdesktop' " +
                "| grep -v grep",
            60,
        )
        if (result.output.isBlank()) {
            paths.appendLog("display diagnostics: nothing to report")
        } else {
            paths.appendLog("display diagnostics:\n${result.output}")
        }
    }

    /** Result of a guest command. */
    data class Result(val exitCode: Int, val output: String, val error: String?) {
        val ok: Boolean get() = exitCode == 0
    }

    companion object {
        private const val TAG = "LinuxRuntime"

        /**
         * The proot command line, without the leading program name.
         *
         * Kept free of Android types so the unit tests can check it against
         * the actual proot binary that ships in the APK: the Termux build
         * rejects any option it does not know with "unknown option '...'",
         * and upstream proot accepts things the Termux build does not (a
         * bare "--" for instance). A mismatch is invisible on the host and
         * fatal on the phone.
         */
        internal fun baseArgsFor(root: String, sdcard: String, sharedTmp: String, shm: String): List<String> = listOf(
            "-r", root,
            "-0",
            // Android forbids hard links in app storage. The X server takes
            // its lock with link(2) and dies with "Linking lock file ...
            // Permission denied"; dpkg needs them too. proot fakes them with
            // symlinks.
            "--link2symlink",
            // Android has no System V IPC. Without the emulation shmget()
            // fails, MIT-SHM is unusable, and GTK windows never paint.
            "--sysvipc",
            "-b", "/dev",
            // Android has no /dev/shm, where programs make the shared memory
            // they pass between processes (shm_open). Firefox's parent
            // process crashes without it. A folder of the app's stands in.
            "-b", "$shm:/dev/shm",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data",
            "-b", "/sdcard",
            "-b", sdcard,
            // All proot processes must share /tmp so the X11 unix socket
            // started by the display process is visible to apps launched
            // in other proot processes.
            "-b", "$sharedTmp:/tmp",
            "-w", "/root",
        )

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