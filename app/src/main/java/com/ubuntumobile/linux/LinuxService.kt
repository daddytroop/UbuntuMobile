package com.ubuntumobile.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps Linux running while the app is not on screen.
 *
 * Without a foreground service Android freezes or kills an app soon after
 * it leaves the screen, and Linux with it: downloads stop, Telegram stops
 * notifying, unsaved work is lost. The service shows a quiet "Linux is
 * running" notification, which also lets Linux apps keep the microphone in
 * the background. Its actions keep the phone awake for long jobs (a partial
 * wake lock, so they go on with the screen off) or stop Linux.
 */
class LinuxService : Service() {
    companion object {
        private const val TAG = "LinuxService"
        private const val CHANNEL = "linux-running"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.ubuntumobile.STOP_LINUX"
        private const val ACTION_AWAKE = "com.ubuntumobile.KEEP_AWAKE"

        @Volatile var keepAwake = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, LinuxService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                // Android refuses while the app is in the background; it is
                // started again when the app comes back.
                Log.w(TAG, "cannot start", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    /** The battery and network for the Linux panel, while Linux runs. */
    private val phoneStatus by lazy { PhoneStatus(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stopping Linux")
                setAwake(false)
                stopForeground(true)
                stopSelf()
                // Linux's processes are the app's: ending the app ends them all.
                android.os.Process.killProcess(android.os.Process.myPid())
                return START_NOT_STICKY
            }
            ACTION_AWAKE -> setAwake(!keepAwake)
        }
        startForeground(NOTIFICATION_ID, notification())
        phoneStatus.start()
        return START_STICKY
    }

    override fun onDestroy() {
        phoneStatus.stop()
        setAwake(false)
        super.onDestroy()
    }

    private fun setAwake(on: Boolean) {
        keepAwake = on
        if (on && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mobuntu:linux")
                .apply { acquire() }
        } else if (!on) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Linux running", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        fun action(name: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, LinuxService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.ubuntumobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Linux is running")
            .setContentText(
                if (keepAwake) "Keeping the phone awake, so long jobs go on with the screen off"
                else "Your apps keep going while you use other apps",
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, if (keepAwake) "Let it sleep" else "Keep awake", action(ACTION_AWAKE, 2))
            .addAction(0, "Stop Linux", action(ACTION_STOP, 3))
            .build()
    }
}
