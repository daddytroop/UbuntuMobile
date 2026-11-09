package com.ubuntumobile.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File

/**
 * Linux apps' notifications, on the phone.
 *
 * linx-notify.py (in the desktop session) sends each one as a JSON line -
 * {"app", "icon", "title", "body"} - to an abstract unix socket here; while
 * the app is in the background it is posted as an Android notification, and
 * tapping it brings the desktop back. In the foreground the desktop shows
 * it already (xfce4-notifyd).
 */
object NotificationBridge {
    private const val TAG = "NotificationBridge"
    private const val CHANNEL = "linux-apps"

    @Volatile private var started = false
    @Volatile private var nextId = 1000

    /** Whether the app is on screen; set by MainActivity. */
    @Volatile var foreground = false

    /** An app's icon by its name, for the notification; set by the launcher's app list. */
    @Volatile var iconFor: (String) -> Bitmap? = { null }

    fun socketName(packageName: String) = "$packageName.notify"

    @Synchronized
    fun start(context: Context, paths: LinuxPaths) {
        val app = context.applicationContext
        if (paths.isInstalled) {
            try {
                File(paths.root, "etc/linx").mkdirs()
                File(paths.root, "etc/linx/notify-socket").writeText(socketName(app.packageName) + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "cannot write the socket name", e)
            }
        }
        if (started) return
        val server = try {
            LocalServerSocket(socketName(app.packageName))
        } catch (e: Exception) {
            Log.w(TAG, "cannot listen", e)
            return
        }
        started = true
        Thread({
            while (true) {
                try {
                    server.accept().use { client ->
                        val line = client.inputStream.bufferedReader().readLine() ?: return@use
                        post(app, JSONObject(line))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "notification failed", e)
                }
            }
        }, "notification-bridge").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Create the notification channel. Called while the app is on screen
     * (when the desktop first comes up): for an app built for older Android,
     * Android asks whether to allow notifications when its first channel is
     * created - and only if the app is in the foreground then.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Linux apps", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifications from the apps on your Linux desktop"
                },
            )
        }
    }

    private fun post(context: Context, note: JSONObject) {
        if (foreground) return
        val app = note.optString("app").ifBlank { "Linux" }
        val title = note.optString("title").ifBlank { app }
        val body = note.optString("body")
            // The spec allows a little markup in bodies.
            .replace(Regex("<[^>]+>"), "")
        ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = open?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.ubuntumobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(app)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
        try {
            iconFor(app)?.let { builder.setLargeIcon(it) }
        } catch (_: Exception) {
        }
        try {
            NotificationManagerCompat.from(context).notify(nextId++, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notifications are not allowed", e)
        }
    }
}
