package com.ubuntumobile.linux

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Filesystem layout for the on-device Ubuntu installation.
 *
 * <pre>
 *   filesDir/
 *     linux/                  <- the proot rootfs (Ubuntu 26.04)
 *     bin/                    <- proot + its shared libraries
 *     home/                   <- $HOME inside the container
 *     .cache/                 <- writable scratch space
 * </pre>
 *
 * The rootfs is shipped as a compressed asset and expanded on first launch.
 */
class LinuxPaths(context: Context) {

    private val app = context.applicationContext

    val root: File = File(app.filesDir, "linux")
    val binDir: File = File(app.filesDir, "bin")
    val homeDir: File = File(app.filesDir, "home")
    val cacheDir: File = File(app.filesDir, ".cache")
    /** Shared /tmp so every proot process sees the same X11 unix socket. */
    val sharedTmpDir: File = File(app.filesDir, "tmp")

    /** Bound over /dev/shm in the container; Android has none. */
    val shmDir: File = File(app.filesDir, "shm")
    val sdcardDir: File get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "shared")

    /** proot executable for the current ABI. */
    val proot: File get() = File(binDir, "proot")

    /** Shared libs proot needs (libtalloc.so.2, libandroid-shmem.so). */
    val libDir: File get() = binDir

    val isInstalled: Boolean
        get() = File(root, "bin/bash").exists() && File(root, "etc/os-release").exists()

    val isProotReady: Boolean
        get() = proot.exists() && proot.canExecute()

    fun ensureDirs() {
        listOf(root, binDir, homeDir, cacheDir, sharedTmpDir, shmDir, sdcardDir).forEach { it.mkdirs() }
    }

    init {
        // The directories have to exist before proot is started: proot
        // refuses to run when a binding points at a path that is not
        // there, and the shared /tmp binding is what lets the display
        // server and the apps it launches see the same X11 socket.
        //
        // This runs on every start, not only during the install, because
        // an app that was already installed still starts proot.
        ensureDirs()
    }

    /** Where the rootfs tarball is unpacked to; also used to detect partial installs. */
    val installMarker: File get() = File(root, ".linx-installed")

    /**
     * A human-readable log of the install.
     *
     * Install failures are handled rather than thrown, so they never reach
     * the crash handler. This file is the record of what happened and can be
     * pulled with:
     *
     * ```
     * adb shell run-as com.ubuntumobile cat files/install.log
     * ```
     */
    val installLog: File get() = File(app.filesDir, "install.log")

    /** Append a line to the install log. */
    fun appendInstallLog(message: String) = appendLog(message)

    /**
     * Append a line to the on-device log.
     *
     * Everything is mirrored into `crash.log`, so a single command pulls
     * the whole story - install, container and display - off the phone:
     *
     * ```
     * adb shell run-as com.ubuntumobile cat files/crash.log
     * ```
     */
    fun appendLog(message: String) {
        try {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val line = "[$stamp] $message\n"
            installLog.appendText(line)
            // Keep it bounded.
            if (installLog.length() > 256 * 1024) {
                installLog.writeText(installLog.readText().takeLast(128 * 1024))
            }
            // Mirror into crash.log so one command shows the whole story.
            val crash = File(app.filesDir, "crash.log")
            crash.appendText(line)
            if (crash.length() > 512 * 1024) {
                crash.writeText(crash.readText().takeLast(256 * 1024))
            }
        } catch (_: Exception) {
        }
    }

    /** The last few lines of the install log, for display on screen. */
    fun installLogTail(lines: Int = 12): String = try {
        installLog.readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) {
        ""
    }

    /**
     * Marker written once the rootfs has been unpacked.
     *
     * It lives outside the rootfs so that a failed first-boot step can be
     * retried without unpacking 330 MB again.
     */
    val extractedMarker: File get() = File(app.filesDir, ".linx-extracted")

    fun markInstalled() {
        // Write it into the rootfs if we can, and always keep a copy
        // outside it, so a read-only rootfs cannot break the install.
        try {
            installMarker.writeText(BuildConfigCompat.ROOTFS_VERSION)
        } catch (_: Exception) {
            // The rootfs directory may not be writable; the copy outside is
            // what actually matters.
        }
        extractedMarker.writeText(BuildConfigCompat.ROOTFS_VERSION)
    }

    fun isMarkedInstalled(): Boolean = extractedMarker.exists()

    /** True when the rootfs has already been unpacked and can be reused. */
    fun isExtracted(): Boolean =
        extractedMarker.exists() && File(root, "usr/bin/bash").exists()

    /** Remove the whole Linux install so it can be re-provisioned. */
    fun wipe() {
        root.deleteRecursively()
        homeDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    companion object {
        const val TAG = "LinuxPaths"
    }
}

/** Build config constants kept in one place so the native layer can read them. */
object BuildConfigCompat {
    /** Bumped whenever the shipped rootfs changes, forcing a re-provision. */
    const val ROOTFS_VERSION = "26.04.1-lite"

    /** ABI-specific asset name for the rootfs archive. */
    fun rootfsAsset(abi: String): String = when (abi) {
        "arm64-v8a" -> "rootfs/ubuntu-26.04-lite-arm64.rootfs"
        "armeabi-v7a" -> "rootfs/ubuntu-26.04-lite-armhf.rootfs"
        "x86_64" -> "rootfs/ubuntu-26.04-lite-amd64.rootfs"
        else -> "rootfs/ubuntu-26.04-lite-amd64.rootfs"
    }

    /** Every rootfs the APK might contain, used as a fallback. */
    val allRootfsAssets: List<String> = listOf(
        "rootfs/ubuntu-26.04-lite-arm64.rootfs",
        "rootfs/ubuntu-26.04-lite-armhf.rootfs",
        "rootfs/ubuntu-26.04-lite-amd64.rootfs",
    )
}
