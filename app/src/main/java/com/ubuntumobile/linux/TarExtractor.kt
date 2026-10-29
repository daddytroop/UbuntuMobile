package com.ubuntumobile.linux

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Streaming tar extractor used to unpack the Ubuntu rootfs.
 *
 * Deliberately free of Android APIs so it can be unit tested on the JVM
 * against the real archive.
 *
 * It handles the tar entry types that matter for a rootfs:
 * directories, regular files, symlinks and hard links. Ubuntu 26.04 is a
 * merged-/usr system, so the archive contains `/bin -> usr/bin` style
 * symlinks that must be recreated exactly.
 */
class TarExtractor(
    private val destination: File,
    private val onProgress: (Long) -> Unit = {},
) {
    /** Result of an extraction run. */
    data class Result(
        val files: Long,
        val bytes: Long,
        val symlinks: Long,
        val directories: Long,
        /** Symlink entries seen in the archive, whether or not they could be created. */
        val symlinkEntries: Long = 0,
        /** Entries that could not be written. */
        val failedEntries: Long = 0,
    )

    /**
     * Extract [source] into [destination].
     *
     * The stream may be gzip compressed; it is detected automatically from
     * the gzip magic bytes.
     */
    fun extract(source: InputStream): Result {
        val raw = BufferedInputStream(source, 256 * 1024)
        val pushback = PushbackInputStream(raw, 2)

        val magic = ByteArray(2)
        val read = pushback.read(magic)
        if (read > 0) pushback.unread(magic, 0, read)
        val gzipped = read == 2 &&
            magic[0] == 0x1f.toByte() &&
            magic[1] == 0x8b.toByte()

        val stream: InputStream = if (gzipped) GZIPInputStream(pushback) else pushback

        var files = 0L
        var bytes = 0L
        var symlinks = 0L
        var symlinkEntries = 0L
        var directories = 0L

        var failedEntries = 0L

        stream.use { input ->
            val header = ByteArray(512)
            // GNU tar stores long paths in a preceding 'L' entry; the actual
            // file name then lives in that entry's data, so we must read it
            // (and skip its bytes) or the whole stream gets misaligned.
            var longName: String? = null
            val dirModes = mutableListOf<Pair<File, Int>>()

            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) break

                val name = cString(header, 0, 100)
                val size = parseOctal(cString(header, 124, 12))
                val mode = parseOctal(cString(header, 100, 8)).toInt()
                val typeFlag = header[156].toInt().toChar()
                val prefix = cString(header, 345, 155)

                // 'L' = GNU long name, 'K' = GNU long link, 'x'/'g' = pax headers.
                if (typeFlag == 'L' || typeFlag == 'K' || typeFlag == 'x' || typeFlag == 'g') {
                    if (typeFlag == 'L' && size in 1..MAX_LONG_NAME) {
                        val data = ByteArray(size.toInt())
                        if (readFully(input, data)) {
                            longName = String(data, Charsets.UTF_8).trimEnd('\u0000')
                        }
                    } else if (size > 0) {
                        skipFully(input, size)
                    }
                    skipFully(input, (512 - (size % 512)) % 512)
                    continue
                }

                val fullName = longName
                    ?: (if (prefix.isNotEmpty()) "$prefix/$name" else name)
                longName = null

                val target = File(destination, fullName)
                // The archive's own root entry ("." or "./") maps onto the
                // extraction destination, which is OUR directory - its mode
                // must not be applied, or the installer could end up unable
                // to write the marker file afterwards.
                val isArchiveRoot = fullName.trim('/').isEmpty() ||
                    fullName.trim('/') == "."

                // A single unwritable entry must not abort the whole
                // extraction: the rootfs is ~20,000 files and a stray
                // problem (a name the filesystem cannot represent, for
                // instance) should not cost the user their whole Ubuntu.
                try {
                when (typeFlag) {
                    '5' -> {
                        target.mkdirs()
                        // Directory permissions are applied at the very end:
                        // a directory may be read-only in the archive, and
                        // applying that immediately would stop us writing
                        // its contents. GNU tar defers them for the same
                        // reason.
                        if (!isArchiveRoot) dirModes += target to mode
                        directories++
                    }
                    '2' -> {
                        val linkTarget = cString(header, 157, 100)
                        symlinkEntries++
                        target.parentFile?.mkdirs()
                        // A previous entry may have created a real directory
                        // where this symlink belongs; remove it first.
                        if (target.exists() && !target.isDirectory) target.delete()
                        if (target.isDirectory) target.deleteRecursively()
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                            symlinks++
                        } catch (_: Exception) {
                            // Fall back to a copy of the target if symlinks
                            // are unavailable (e.g. some filesystems).
                            try {
                                val resolved = File(destination, linkTarget)
                                if (resolved.exists()) {
                                    resolved.copyTo(target, overwrite = true)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    '0', '\u0000', '7' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> copyExactly(input, out, size) }
                        // The permission bits live in the tar header; without
                        // them every binary would land non-executable and the
                        // container could not start a shell.
                        applyMode(target, mode)
                        files++
                        bytes += size
                    }
                    '1' -> {
                        val linkTarget = cString(header, 157, 100)
                        target.parentFile?.mkdirs()
                        try {
                            java.nio.file.Files.createLink(
                                target.toPath(),
                                File(destination, linkTarget).toPath(),
                            )
                            files++
                        } catch (_: Exception) {
                        }
                    }
                    else -> {
                        // Unknown entry types still carry data that has to be
                        // consumed, otherwise the next header read is garbage.
                        if (size > 0) skipFully(input, size)
                    }
                }
                } catch (e: Exception) {
                    // Keep going, but make sure the entry's payload is
                    // consumed so the stream stays in sync.
                    failedEntries++
                    try {
                        if (size > 0) skipFully(input, size)
                    } catch (_: Exception) {
                    }
                }

                // Tar pads entries to a multiple of 512 bytes.
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) skipFully(input, pad)

                onProgress(bytes)
            }

            // Now that everything has been written, apply the directory
            // permissions that were recorded in the archive.
            for ((dir, dirMode) in dirModes.asReversed()) {
                applyMode(dir, dirMode)
            }

            // The extraction destination belongs to us, not to the archive:
            // make sure the installer can still write into it afterwards.
            applyMode(destination, OWNER_RWX)
        }

        return Result(files, bytes, symlinks, directories, symlinkEntries, failedEntries)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            output.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun cString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(s: String): Long {
        val trimmed = s.trim { it == '\u0000' || it == ' ' }
        if (trimmed.isEmpty()) return 0
        return try {
            trimmed.toLong(8)
        } catch (_: NumberFormatException) {
            0
        }
    }

    /**
     * Apply the permission bits recorded in the tar header.
     *
     * Without this every extracted file would be created with the process
     * umask (0644) and no binaries would be executable - the container
     * could not even start bash.
     *
     * The actual chmod is pluggable: on Android we use
     * `android.system.Os.chmod`, which is a plain POSIX chmod. The
     * `java.io.File` helpers have surprising group/other semantics on
     * Android and can leave a directory without the owner's write bit.
     */
    private fun applyMode(file: File, mode: Int) {
        chmod(file, mode)
    }

    /**
     * Applies a POSIX mode to [file]. Replaced on Android with
     * `android.system.Os.chmod`.
     *
     * The default implementation uses `java.nio.file` POSIX permissions,
     * which are exact. The `java.io.File.setReadable/setWritable/
     * setExecutable` helpers are NOT: their "group/other" handling can
     * clobber the owner's bits, which leaves directories without an
     * execute bit (so files inside become invisible) or without a write
     * bit (so the installer cannot write into them).
     */
    var chmod: (File, Int) -> Unit = { file, mode ->
        try {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString(
                modeToPosixString(mode)
            )
            java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: Exception) {
            // No POSIX permissions on this filesystem (Windows): keep the
            // file usable.
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(true, true)
        }
    }

    /** Render a numeric POSIX mode as the "rwxr-xr-x" form. */
    private fun modeToPosixString(mode: Int): String {
        val sb = StringBuilder(9)
        for (shift in intArrayOf(6, 3, 0)) {
            val bits = (mode shr shift) and 7
            sb.append(if (bits and 4 != 0) 'r' else '-')
            sb.append(if (bits and 2 != 0) 'w' else '-')
            sb.append(if (bits and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }

    private companion object {
        /** Sanity limit for a GNU long-name entry. */
        const val MAX_LONG_NAME = 64 * 1024

        /** rwx for the owner. */
        const val OWNER_RWX = 0x1C0
    }
}
