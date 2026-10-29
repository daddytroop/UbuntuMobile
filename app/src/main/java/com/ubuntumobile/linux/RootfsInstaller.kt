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
                // Do not abort here: the container smoke test below gives a
                // far better error message than "could not install the
                // runtime", because it runs proot and shows what it said.
                paths.appendInstallLog("the proot runtime could not be installed")
                Log.w(TAG, "proot runtime install reported a problem, continuing to the smoke test")
            }

            // 2. copy the rootfs out of the APK
            val assetName = resolveRootfsAsset()
            val archive = File(paths.cacheDir, "rootfs.archive")
            val total = copyAssetToCache(assetName, archive) { copied, size ->
                onProgress(Progress(Progress.Phase.PREPARING, copied, size, "Unpacking Ubuntu 26.04…"))
            }

            // 3. unpack it - unless a previous attempt already finished
            //    this step, so a retry does not redo 330 MB of work.
            if (paths.isExtracted()) {
                Log.i(TAG, "rootfs already unpacked, reusing it")
                onProgress(Progress(Progress.Phase.EXTRACTING, total, total, "Unpacking Ubuntu…"))
            } else {
                clearRootfs()
                paths.root.mkdirs()
                onProgress(Progress(Progress.Phase.EXTRACTING, 0, total, "Unpacking Ubuntu…"))
                archive.inputStream().use { input ->
                    val extractor = TarExtractor(paths.root) { copied ->
                        onProgress(Progress(Progress.Phase.EXTRACTING, copied, total, "Unpacking Ubuntu…"))
                    }
                    // Use a plain POSIX chmod on Android: java.io.File's
                    // permission helpers have surprising group/other
                    // semantics there and can leave a directory without the
                    // owner's write bit.
                    extractor.chmod = { file, mode ->
                        try {
                            android.system.Os.chmod(file.absolutePath, mode)
                        } catch (_: Exception) {
                            // Fall back to the Java helpers.
                            file.setReadable(true, true)
                            file.setWritable(true, true)
                            file.setExecutable(true, true)
                        }
                    }
                    val result = extractor.extract(input)
                    Log.i(
                        TAG,
                        "extracted ${result.files} files, ${result.bytes} bytes, " +
                            "${result.symlinkEntries} symlinks, ${result.failedEntries} failed",
                    )
                    if (result.failedEntries > 0) {
                        Log.w(TAG, "${result.failedEntries} entries could not be extracted")
                    }
                }
                // Record that the unpack finished, so a failed first-boot
                // step can be retried without unpacking again.
                paths.extractedMarker.writeText(BuildConfigCompat.ROOTFS_VERSION)
            }
            archive.delete()

            // 4. first boot inside the container: locale + Flathub
            onProgress(Progress(Progress.Phase.FIRSTBOOT, 0, total, "Configuring Flathub…"))
            val first = runtime.shell("/usr/local/lib/linx/firstboot.sh", timeoutSeconds = 300)
            Log.i(TAG, "firstboot rc=${first.exitCode}\n${first.output.take(2000)}")

            // 5. smoke test: prove the container actually starts a shell,
            //    so a failure here is reported with the real reason instead
            //    of surfacing later as "nothing works".
            onProgress(Progress(Progress.Phase.FIRSTBOOT, 0, total, "Checking Ubuntu…"))
            val smoke = runtime.shell(
                "echo LINUX_SMOKE_OK; uname -sr; head -1 /etc/os-release",
                timeoutSeconds = 120,
            )
            paths.appendInstallLog("smoke rc=${smoke.exitCode} out=${smoke.output.take(400)}")
            Log.i(TAG, "smoke rc=${smoke.exitCode}\n${smoke.output.take(2000)}")
            if (!smoke.output.contains("LINUX_SMOKE_OK")) {
                val detail = smoke.error ?: smoke.output.trim().take(600)
                throw IOException(
                    "Ubuntu did not start: " +
                        (if (detail.isNotEmpty()) detail else "the container produced no output")
                )
            }

            paths.markInstalled()
            onProgress(Progress(Progress.Phase.DONE, total, total, "Ubuntu 26.04 is ready"))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            paths.appendInstallLog("install failed: ${e.message}")
            val detail = paths.installLogTail(10)
            onProgress(
                Progress(
                    Progress.Phase.FAILED,
                    detail = if (detail.isNotEmpty()) detail else (e.message ?: "Install failed"),
                )
            )
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
     * Remove a previous rootfs.
     *
     * A tree that was left read-only cannot be deleted (removing an entry
     * needs write permission on its parent), so make it writable first and
     * carry on even if the cleanup is incomplete - extracting over the top
     * works either way.
     */
    private fun clearRootfs() {
        val root = paths.root
        if (!root.exists()) return
        try {
            android.system.Os.chmod(root.absolutePath, 0x1C0)
        } catch (_: Exception) {
        }
        try {
            root.walkBottomUp().forEach { dir ->
                try {
                    android.system.Os.chmod(dir.absolutePath, 0x1C0)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            root.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "could not fully remove the old rootfs: ${e.message}")
        }
    }

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
