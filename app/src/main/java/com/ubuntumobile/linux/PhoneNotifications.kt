package com.ubuntumobile.linux

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The phone's notifications on the Linux desktop, while it is in sight.
 *
 * Docked at a monitor, the phone is face down or out of reach; a WhatsApp
 * message should show on the screen being looked at. With access granted
 * (Settings → Phone notifications), each new notification is left in the
 * shared /tmp, where linx-notify.py shows it with notify-send - in the
 * desktop's own pop-up and in the panel's bell. Only while the desktop is
 * on screen: otherwise the phone shows it, as ever.
 */
class PhoneNotifications : NotificationListenerService() {
    companion object {
        private const val TAG = "PhoneNotifications"
        private const val PREF = "phone_notes"

        fun enabled(context: Context) =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        }

        /** Whether Android lets the app see notifications. */
        fun granted(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, PhoneNotifications::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun askForAccess(context: Context) {
            try {
                context.startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w(TAG, "no listener settings", e)
            }
        }

        /** A notification for the Linux desktop (linx-notify.py shows it). */
        fun toLinux(context: Context, app: String, title: String, body: String, urgent: Boolean = false, icon: String = "phone") {
            try {
                val dir = File(LinuxPaths(context).sharedTmpDir, "linx-phone-notes").apply { mkdirs() }
                val json = JSONObject()
                    .put("app", app).put("title", title).put("body", body)
                    .put("urgent", urgent).put("icon", icon)
                val name = "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
                val tmp = File(dir, ".$name")
                tmp.writeText(json.toString())
                tmp.renameTo(File(dir, "$name.json"))
            } catch (e: Exception) {
                Log.w(TAG, "cannot pass a notification", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!com.ubuntumobile.display.PhoneExtras.desktopVisible || !enabled(this)) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        // Not the "playing music" and "downloading" kind, nor a group's summary.
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_TRANSPORT) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        toLinux(this, app, title, body.take(400))
    }
}
