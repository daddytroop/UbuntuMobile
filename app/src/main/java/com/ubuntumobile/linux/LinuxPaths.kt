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
        listOf(root, binDir, homeDir, cacheDir, sdcardDir).forEach { it.mkdirs() }
    }

    /** Where the rootfs tarball is unpacked to; also used to detect partial installs. */
    val installMarker: File get() = File(root, ".linx-installed")

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
