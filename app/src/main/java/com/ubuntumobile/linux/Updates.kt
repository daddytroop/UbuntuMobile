package com.ubuntumobile.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** An update waiting: an Ubuntu package or a Flathub app (linx-updates.sh --check). */
data class UpdateItem(val flatpak: Boolean, val id: String, val version: String)

/**
 * Updates for Linux, in one place: linx-updates.sh finds and installs them,
 * Software's Updates tab shows them, and the phone says when there are some.
 */
object Updates {
    private const val CHANNEL = "linux-updates"
    private const val NOTIFICATION_ID = 7

    /** The intent action that opens Software on its Updates tab. */
    const val ACTION_SHOW = "com.ubuntumobile.SHOW_UPDATES"

    const val CHECK = "/usr/local/lib/linx/linx-updates.sh --check"

    fun parse(output: String): List<UpdateItem> = output.lines().mapNotNull { line ->
        val p = line.trim().split("|")
        if (p.size < 3 || p[1].isBlank() || p[0] !in setOf("deb", "flatpak")) null
        else UpdateItem(p[0] == "flatpak", p[1], p[2])
    }

    /**
     * How far an update is, 0-100, from the last line of its progress file:
     * apt's "dlstatus:1:42.5:..." and "pmstatus:pkg:42.5:..." lines, and
     * Flatpak's "flatpak:42%".
     */
    fun progress(lastLine: String?): Int? {
        val line = lastLine?.trim() ?: return null
        val p = line.split(":")
        return when {
            p.size >= 3 && (p[0] == "dlstatus" || p[0] == "pmstatus") -> p[2].toFloatOrNull()?.toInt()
            p.size >= 2 && p[0] == "flatpak" -> p[1].removeSuffix("%").toIntOrNull()
            else -> null
        }?.coerceIn(0, 100)
    }

    /** Say on the phone that [count] updates are waiting; none clears it. */
    fun notify(context: Context, count: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (count <= 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Linux updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setAction(ACTION_SHOW)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = open?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text = if (count == 1) "1 update is ready for Linux" else "$count updates are ready for Linux"
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.ubuntumobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Updates")
            .setContentText("$text. Tap to install them.")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, note)
        } catch (e: SecurityException) {
            Log.w("Updates", "notifications are not allowed", e)
        }
    }
}
