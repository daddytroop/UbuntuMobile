package com.ubuntumobile.linux

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Installs the proot binary and its shared libraries from bundled assets.
 *
 * The proot binary is a Termux build (bionic libc, Android-native), so it runs
 * directly on the device without any extra runtime.
 */
object ProotInstaller {

    private const val TAG = "ProotInstaller"

    /** Maps Android ABI -> the asset suffix used for the proot payload. */
    private fun abiTag(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> "x86_64"
    }

    /** Files shipped inside the `proot/<abi>/` asset folder. */
    private val payloadFiles = listOf(
        "proot" to "proot",
        "loader" to "loader",
        "loader32" to "loader32",
        "libtalloc.so" to "libtalloc.so",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    /**
     * Files that have to be executable for proot to run.
     *
     * The 32-bit build has no separate loader32 - its `loader` is the
     * 32-bit one - so it is optional.
     */
    private val executables = setOf("proot", "loader", "loader32")

    fun install(context: Context, paths: LinuxPaths): Boolean {
        val tag = abiTag()
        return try {
            paths.binDir.mkdirs()
            for ((assetName, targetName) in payloadFiles) {
                val assetPath = "proot/$tag/$assetName"
                val target = File(paths.binDir, targetName)

                // loader32 only exists for the 64-bit ABIs.
                if (assetName == "loader32" && !hasAsset(context, assetPath)) continue

                if (!target.exists() || target.length() == 0L) {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                }

                // Set the mode with a real POSIX chmod. The java.io.File
                // helpers can clobber the owner's bits on Android, which
                // leaves the binary without an execute bit and every exec
                // fails with "Permission denied".
                val mode = if (targetName in executables) 0x1ED else 0x1A4
                setMode(target, mode)
                Log.i(TAG, "installed $targetName (${target.length()} bytes, mode ${mode.toString(8)})")
            }

            if (!paths.proot.canExecute()) {
                val msg = "proot is not executable after install"
                Log.e(TAG, msg)
                paths.appendInstallLog(msg)
                return false
            }

            // Try to run it. This is a diagnostic rather than a gate: the
            // container smoke test is the real authority, and it produces a
            // much better message. We try a couple of environments because
            // Android is picky about what a child process inherits.
            val attempts = listOf(
                "proot" to mapOf(
                    "PATH" to "/system/bin:/system/xbin",
                    "LD_LIBRARY_PATH" to paths.libDir.absolutePath,
                    "HOME" to paths.homeDir.absolutePath,
                    "TMPDIR" to paths.cacheDir.absolutePath,
                    "PROOT_LOADER" to File(paths.libDir, "loader").absolutePath,
                    "PROOT_LOADER_32" to File(paths.libDir, "loader32").absolutePath,
                ),
                "proot (inherited environment)" to emptyMap(),
            )
            for ((label, env) in attempts) {
                try {
                    val pb = ProcessBuilder(paths.proot.absolutePath, "--version")
                    pb.environment().apply {
                        if (env.isNotEmpty()) {
                            clear()
                            putAll(env)
                        }
                    }
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    val code = proc.waitFor()
                    paths.appendInstallLog("$label -> exit $code: $out")
                    Log.i(TAG, "$label -> exit $code: $out")
                } catch (e: Exception) {
                    paths.appendInstallLog("$label -> ${e.message}")
                    Log.w(TAG, "$label failed", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "proot install failed", e)
            false
        }
    }

    /** Apply a POSIX mode; the fallback is only used if the OS refuses. */
    private fun setMode(file: File, mode: Int) {
        try {
            android.system.Os.chmod(file.absolutePath, mode)
        } catch (_: Exception) {
            file.setReadable(true, false)
            file.setWritable(true, false)
            if (mode and 0x49 == 0x49) file.setExecutable(true, false)
        }
    }

    /** True when the APK contains the given asset. */
    private fun hasAsset(context: Context, path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (_: Exception) {
        false
    }
}
