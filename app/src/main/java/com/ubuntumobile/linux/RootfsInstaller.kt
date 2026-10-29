package com.ubuntumobile.linux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Provisions the Ubuntu 26.04 rootfs on the device.
 *
 * The rootfs ships as a gzipped tar inside the APK assets and is expanded
 * into the app's private storage on first launch. The app's private dir is
 * on internal storage, which is fast and not world-readable.
 */
class RootfsInstaller(
    private val context: Context,
    private val paths: LinuxPaths,
    private val runtime: LinuxRuntime,
) {

    /** Progress of the install, surfaced in the UI. */
    data class Progress(
        val phase: Phase,
        val bytesCopied: Long = 0,
        val totalBytes: Long = 0,
        val detail: String = "",
    ) {
        enum class Phase { IDLE, PREPARING, EXTRACTING, FIRSTBOOT, DONE, FAILED }

        val fraction: Float
            get() = if (totalBytes > 0) (bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /**
     * Install (or reinstall) Ubuntu.
     *
     * @param onProgress invoked on a worker thread with progress updates.
     */
    suspend fun install(onProgress: (Progress) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            paths.ensureDirs()

            onProgress(Progress(Progress.Phase.PREPARING, detail = "Preparing…"))

            // 1. proot binary, loader and shared libraries
            if (!ProotInstaller.install(context, paths)) {
                return@withContext Result.failure(IOException("Could not install the proot runtime"))
            }

            // 2. copy the rootfs out of the APK
            val assetName = resolveRootfsAsset()
            val archive = File(paths.cacheDir, "rootfs.archive")
            val total = copyAssetToCache(assetName, archive) { copied, size ->
                onProgress(Progress(Progress.Phase.PREPARING, copied, size, "Unpacking Ubuntu 26.04…"))
            }

            // 3. unpack it
            paths.root.deleteRecursively()
            paths.root.mkdirs()
            onProgress(Progress(Progress.Phase.EXTRACTING, 0, total, "Unpacking Ubuntu…"))
            archive.inputStream().use { input ->
                TarExtractor(paths.root) { copied ->
                    onProgress(Progress(Progress.Phase.EXTRACTING, copied, total, "Unpacking Ubuntu…"))
                }.extract(input)
            }
            archive.delete()

            // 4. first boot inside the container: locale + Flathub
            onProgress(Progress(Progress.Phase.FIRSTBOOT, 0, total, "Configuring Flathub…"))
            val first = runtime.shell("/usr/local/lib/linx/firstboot.sh", timeoutSeconds = 300)
            Log.i(TAG, "firstboot rc=${first.exitCode}\n${first.output.take(2000)}")

            paths.markInstalled()
            onProgress(Progress(Progress.Phase.DONE, total, total, "Ubuntu 26.04 is ready"))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            onProgress(Progress(Progress.Phase.FAILED, detail = e.message ?: "Install failed"))
            Result.failure(e)
        }
    }

    /** Remove the installed Ubuntu so it can be re-provisioned. */
    suspend fun uninstall(): Unit = withContext(Dispatchers.IO) {
        paths.wipe()
        paths.ensureDirs()
    }

    private fun currentAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "x86_64"

    /**
     * Find the rootfs archive to unpack.
     *
     * The build ships one rootfs per ABI flavor, so we prefer the flavor's
     * asset and fall back to any rootfs present in the APK.
     */
    private fun resolveRootfsAsset(): String {
        val candidates = buildList {
            add(com.ubuntumobile.BuildConfig.ROOTFS_ASSET)
            add(BuildConfigCompat.rootfsAsset(currentAbi()))
            addAll(BuildConfigCompat.allRootfsAssets)
        }.distinct()

        for (name in candidates) {
            try {
                context.assets.open(name).close()
                Log.i(TAG, "using rootfs asset $name")
                return name
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        throw IOException("No Ubuntu rootfs found in the APK")
    }

    private fun copyAssetToCache(
        assetName: String,
        out: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val asset = context.assets.open(assetName)
        val total = asset.available().toLong()
        var copied = 0L
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = asset.read(buf)
                if (n <= 0) break
                fos.write(buf, 0, n)
                copied += n
                onProgress(copied, total)
            }
            fos.flush()
        }
        asset.close()
        return total
    }

    companion object {
        private const val TAG = "RootfsInstaller"
    }
}
