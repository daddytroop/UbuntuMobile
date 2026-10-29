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
        "libtalloc.so.2" to "libtalloc.so.2",
        "libandroid-shmem.so" to "libandroid-shmem.so",
    )

    fun install(context: Context, paths: LinuxPaths): Boolean {
        val tag = abiTag()
        return try {
            paths.binDir.mkdirs()
            for ((assetName, targetName) in payloadFiles) {
                val assetPath = "proot/$tag/$assetName"
                val target = File(paths.binDir, targetName)
                if (target.exists() && target.length() > 0) continue

                context.assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                target.setReadable(true, false)
                target.setWritable(true, false)
                target.setExecutable(true, false)
                Log.i(TAG, "installed $targetName (${target.length()} bytes)")
            }
            // proot and its loader must be executable, and the loader path is
            // baked into the binary, so we point proot at our own copy.
            paths.proot.setExecutable(true, false)
            File(paths.binDir, "loader").setExecutable(true, false)
            File(paths.binDir, "loader32").setExecutable(true, false)
            paths.proot.exists() && paths.proot.canExecute()
        } catch (e: Exception) {
            Log.e(TAG, "proot install failed", e)
            false
        }
    }
}
