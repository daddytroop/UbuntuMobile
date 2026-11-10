package com.ubuntumobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Something shared to Mobuntu from another Android app ("Open in Mobuntu"
 * in the share menu, or Open with): files, or a piece of text or a link.
 */
data class SharedIn(val uris: List<Uri>, val text: String?) {
    companion object {
        /** What [intent] shares, or null when it is not a share. */
        fun from(intent: Intent?): SharedIn? {
            intent ?: return null
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(stream(intent))
                Intent.ACTION_SEND_MULTIPLE -> streams(intent)
                Intent.ACTION_VIEW -> listOfNotNull(intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" })
                else -> return null
            }
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            return if (uris.isEmpty() && text == null) null else SharedIn(uris, text)
        }

        @Suppress("DEPRECATION")
        private fun stream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streams(intent: Intent): List<Uri> =
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

        /** A link on its own, which opens as it is rather than as a note. */
        fun linkIn(text: String): String? =
            text.trim().takeIf { it.matches(Regex("(?i)https?://\\S+")) }
    }

    /**
     * Put the files where Linux sees them, /root/From phone, and return
     * what to open: their paths in Linux, or the link.
     */
    fun copyInto(context: Context, root: File): List<String> {
        linkIn(text.orEmpty())?.takeIf { uris.isEmpty() }?.let { return listOf(it) }
        val dir = File(root, "root/$SHARED_FOLDER")
        dir.mkdirs()
        val out = mutableListOf<String>()
        for (uri in uris) {
            val name = safeName(displayName(context, uri) ?: uri.lastPathSegment ?: "shared")
            val dest = unique(dir, name)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: continue
                out += "/root/$SHARED_FOLDER/${dest.name}"
            } catch (_: Exception) {
                dest.delete()
            }
        }
        if (uris.isEmpty() && text != null) {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
            val dest = unique(dir, "Shared text $stamp.txt")
            dest.writeText(text)
            out += "/root/$SHARED_FOLDER/${dest.name}"
        }
        return out
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun unique(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 2
        while (file.exists()) {
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            file = File(dir, name.substring(0, dot) + " ($n)" + name.substring(dot))
            n++
        }
        return file
    }
}

/** The folder in the Linux home where shared files land. */
const val SHARED_FOLDER = "From phone"

/** A file name that is safe in Linux: no slashes, nothing hidden. */
internal fun safeName(name: String): String =
    name.replace('/', '_').replace('\u0000', '_').trimStart('.').ifBlank { "shared" }.take(200)
