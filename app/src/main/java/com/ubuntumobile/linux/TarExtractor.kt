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

        stream.use { input ->
            val header = ByteArray(512)
            // GNU tar stores long paths in a preceding 'L' entry; the actual
            // file name then lives in that entry's data, so we must read it
            // (and skip its bytes) or the whole stream gets misaligned.
            var longName: String? = null

            while (true) {
                if (!readFully(input, header)) break
                if (header.all { it == 0.toByte() }) break

                val name = cString(header, 0, 100)
                val size = parseOctal(cString(header, 124, 12))
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

                when (typeFlag) {
                    '5' -> {
                        target.mkdirs()
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

                // Tar pads entries to a multiple of 512 bytes.
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) skipFully(input, pad)

                onProgress(bytes)
            }
        }

        return Result(files, bytes, symlinks, directories, symlinkEntries)
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

    private companion object {
        /** Sanity limit for a GNU long-name entry. */
        const val MAX_LONG_NAME = 64 * 1024
    }
}
