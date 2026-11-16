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
 *
 * The same socket takes what Linux hands to the phone (linx-phone):
 * {"open": uri} opens a phone number, an email address, a place or a link
 * with the phone's own apps, {"app": package} starts an Android app, and
 * {"settings": page} opens a page of the phone's settings.
 */
object NotificationBridge {
    private const val TAG = "NotificationBridge"
    private const val CHANNEL = "linux-apps"

    @Volatile private var started = false
    @Volatile private var nextId = 1000

    /** Whether the app is on screen; set by MainActivity. */
    @Volatile var foreground = false

    /** The monitor the desktop is on when docked, for Android apps started from Linux. */
    @Volatile var launchDisplayId: Int? = null

    /** The links Linux may hand over: the phone's kinds of thing, and the web. */
    private val PHONE_SCHEMES = setOf("tel", "sms", "smsto", "geo", "mailto", "http", "https", "market")

    /** An app's icon by its name, for the notification; set by the launcher's app list. */
    @Volatile var iconFor: (String) -> Bitmap? = { null }

    fun socketName(packageName: String) = "$packageName.notify"

    // The desktop's launcher, connected (linx-windows.py), or null.
    private val launcherLock = Any()
    private var launcher: android.net.LocalSocket? = null
    private var launcherReader: java.io.BufferedReader? = null

    /**
     * Start a Linux program from inside the desktop's session, through the
     * launcher there, rather than in a proot of its own: one process instead
     * of two, which matters under Android's limit of 32 child processes.
     * [name] is what to call it if it stops before opening a window.
     *
     * False when there is no launcher to take it (the desktop is not up, or
     * is an older one): the caller then starts it the old way.
     */
    fun launch(argv: List<String>, env: Map<String, String>, name: String?): Boolean = synchronized(launcherLock) {
        val socket = launcher ?: return false
        try {
            val request = JSONObject()
                .put("argv", org.json.JSONArray(argv))
                .put("env", JSONObject(env))
            if (name != null) request.put("name", name)
            socket.outputStream.write((request.toString() + "\n").toByteArray())
            socket.outputStream.flush()
            // It says "ok" when it has the request; silence means it is gone.
            socket.soTimeout = 2500
            launcherReader?.readLine() == "ok"
        } catch (e: Exception) {
            Log.w(TAG, "the launcher did not answer", e)
            false
        }.also { taken ->
            if (!taken) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                launcher = null
                launcherReader = null
            }
        }
    }

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
                    val accepted = server.accept()
                    val reader = accepted.inputStream.bufferedReader()
                    val first = reader.readLine()
                    if (first != null && JSONObject(first).has("launcher")) {
                        // The desktop's launcher (linx-windows.py): the
                        // connection stays, for launch() to write to.
                        synchronized(launcherLock) {
                            try {
                                launcher?.close()
                            } catch (_: Exception) {
                            }
                            launcher = accepted
                            launcherReader = reader
                        }
                        continue
                    }
                    accepted.use { _ ->
                        val line = first ?: return@use
                        val message = JSONObject(line)
                        when {
                            message.has("open") -> openOnPhone(app, message.getString("open"))
                            message.has("app") -> startAndroidApp(app, message.getString("app"))
                            message.has("settings") -> openSettings(app, message.getString("settings"))
                            // The desktop's own: the app shows them over the desktop.
                            message.has("quick") -> com.ubuntumobile.display.PhoneRequests.post("quick")
                            message.has("screenshot") -> com.ubuntumobile.display.PhoneRequests.post("screenshot")
                            message.has("lock") -> com.ubuntumobile.display.PhoneRequests.post("lock")
                            // Linux's Settings → Display: the app's settings.
                            message.has("mobuntu") -> com.ubuntumobile.display.PhoneRequests.post("mobuntu-settings")
                            // Steam on an ARM phone: DroidDeck, or where to get it.
                            message.has("steam") -> com.ubuntumobile.ui.DroidDeck.open(app)
                            message.has("print") -> com.ubuntumobile.display.PhoneRequests.post("print:" + message.getString("print"))
                            else -> post(app, message)
                        }
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

    private fun openOnPhone(context: Context, uri: String) {
        val parsed = android.net.Uri.parse(uri)
        if (parsed.scheme?.lowercase() !in PHONE_SCHEMES) {
            Log.w(TAG, "not opening ${parsed.scheme}: links on the phone only")
            return
        }
        val action = if (parsed.scheme.equals("mailto", ignoreCase = true)) Intent.ACTION_SENDTO else Intent.ACTION_VIEW
        start(context, Intent(action, parsed))
    }

    /** A page of the phone's settings, from the panel's battery and network items. */
    private fun openSettings(context: Context, page: String) {
        val action = when (page) {
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "internet" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                android.provider.Settings.ACTION_WIRELESS_SETTINGS
            }
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            else -> return
        }
        start(context, Intent(action))
    }

    private fun startAndroidApp(context: Context, pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        start(context, launch)
    }

    private fun start(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val display = launchDisplayId
        try {
            if (display != null) {
                // Docked: next to the desktop on the monitor, not on the phone.
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display)
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot open $intent", e)
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
