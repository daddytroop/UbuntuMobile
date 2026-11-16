package com.ubuntumobile.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ubuntumobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * New versions of Mobuntu itself, from the project's GitHub releases.
 *
 * The app checks now and then, says so on the phone and in Settings, and
 * installs the new version over itself: the APK goes straight into
 * Android's installer while it downloads, and Android replaces the app in
 * place. Linux, its apps and files live in the app's data, which an update
 * keeps - nothing to uninstall, nothing lost. (Every release is signed
 * with the same key, which is what lets one install over another.)
 */
object AppUpdate {
    /** Where the releases are. */
    const val REPO = "daddytroop/UbuntuMobile"

    /** Opens Settings, where the update is. */
    const val ACTION_SHOW = "com.ubuntumobile.SHOW_APP_UPDATE"
    private const val ACTION_STATUS = "com.ubuntumobile.APP_UPDATE_STATUS"
    private const val CHANNEL = "app-updates"
    private const val NOTIFICATION_ID = 8
    private const val PREFS = "app-update"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val TAG = "AppUpdate"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    var state: State by mutableStateOf(State.Idle)
        private set

    /** This app's version, without "-debug". */
    fun current(): String = BuildConfig.VERSION_NAME.substringBefore("-")

    /** Whether version [a] is later than [b] ("0.18.1" > "0.18.0"). */
    internal fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".").map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** GitHub's "latest release" answer: its version and this phone's APK, or null. */
    internal fun parse(json: String, flavor: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = o.optJSONArray("assets") ?: return null
        val want = "app-$flavor-release.apk"
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == want) {
                return Release(version, o.optString("body"), a.optString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** GitHub's API; a debug build can be pointed at a test server (files/update-api). */
    private fun apiBase(context: Context): String {
        if (BuildConfig.DEBUG) {
            try {
                return java.io.File(context.filesDir, "update-api").readText().trim().trimEnd('/')
            } catch (_: Exception) {
            }
        }
        return "https://api.github.com"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Mobuntu-app")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /**
     * Look for a newer version. [quiet] is the check the app makes by
     * itself: at most every six hours, it says nothing when there is no
     * update or no answer, and puts a new version on the phone once.
     */
    suspend fun check(context: Context, quiet: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (quiet) {
            if (state !is State.Idle && state !is State.UpToDate) return
            if (System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_EVERY_MS) {
                // The last answer, without asking again.
                prefs.getString("release", null)?.let { saved ->
                    runCatching { parse(saved, BuildConfig.FLAVOR) }.getOrNull()
                        ?.takeIf { newer(it.version, current()) }
                        ?.let { state = State.Available(it) }
                }
                return
            }
        }
        if (state is State.Downloading || state is State.Installing) return
        state = State.Checking
        val found = withContext(Dispatchers.IO) {
            try {
                val c = open(apiBase(context) + "/repos/$REPO/releases/latest")
                try {
                    if (c.responseCode != 200) error("GitHub answered ${c.responseCode}")
                    val body = c.inputStream.bufferedReader().readText()
                    prefs.edit().putLong("checked", System.currentTimeMillis()).putString("release", body).apply()
                    Result.success(parse(body, BuildConfig.FLAVOR))
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "no answer about updates", e)
                Result.failure(e)
            }
        }
        val release = found.getOrNull()
        state = when {
            found.isFailure -> if (quiet) State.Idle else State.Failed("Could not reach GitHub. Check the connection and try again.", null)
            release != null && newer(release.version, current()) -> State.Available(release)
            else -> State.UpToDate
        }
        val available = (state as? State.Available)?.release ?: return
        if (quiet && prefs.getString("notified", null) != available.version) {
            prefs.edit().putString("notified", available.version).apply()
            notify(context, available)
        }
    }

    private fun notify(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Mobuntu updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setAction(ACTION_SHOW)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = open?.let {
            PendingIntent.getActivity(context, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.ubuntumobile.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Mobuntu ${release.version} is available")
            .setContentText("Tap to update. Your Linux, apps and files stay as they are.")
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, note)
        } catch (e: SecurityException) {
            Log.w(TAG, "notifications are not allowed", e)
        }
    }

    /** Android lets this app install updates (Settings → Install unknown apps). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Download [release] into Android's installer and hand it over: Android
     * asks the user once more, then replaces the app (which closes it, and
     * Linux with it, for a moment).
     */
    suspend fun install(context: Context, release: Release) {
        if (state is State.Downloading || state is State.Installing) return
        state = State.Downloading(release, 0f)
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val result = withContext(Dispatchers.IO) {
            var session: PackageInstaller.Session? = null
            var id = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (release.size > 0) params.setSize(release.size)
                id = installer.createSession(params)
                session = installer.openSession(id)
                val c = open(release.apkUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
                try {
                    if (c.responseCode != 200) error("the download answered ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
                    session.openWrite("mobuntu.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                        c.inputStream.use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var done = 0L
                            var shown = 0f
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (total > 0) {
                                    val f = done.toFloat() / total
                                    if (f - shown >= 0.01f) {
                                        shown = f
                                        state = State.Downloading(release, f)
                                    }
                                }
                            }
                        }
                        session.fsync(out)
                    }
                } finally {
                    c.disconnect()
                }
                val status = Intent(ACTION_STATUS).setPackage(app.packageName).setClass(app, Receiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                state = State.Installing(release)
                session.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
                session.close()
                null
            } catch (e: Exception) {
                Log.w(TAG, "the update did not download", e)
                try {
                    session?.abandon()
                } catch (_: Exception) {
                }
                e.message ?: e.toString()
            }
        }
        if (result != null) state = State.Failed("The download stopped: $result. Try again.", release)
    }

    /**
     * After an update: Android does not open the app again, so a
     * notification says it is done and opens it.
     */
    class Updated : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Mobuntu updates", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = open?.let {
                PendingIntent.getActivity(context, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.ubuntumobile.R.drawable.ic_launcher_monochrome)
                .setContentTitle("Mobuntu is updated to ${current()}")
                .setContentText("Tap to open it. Linux, your apps and files are as you left them.")
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, note)
            } catch (e: SecurityException) {
                Log.w(TAG, "notifications are not allowed", e)
            }
        }
    }

    /** Android's answers about the install. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val release = (state as? State.Installing)?.release
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        state = State.Failed("Android did not show the installer.", release)
                    }
                }
                // The new version takes over; this one does not hear more.
                PackageInstaller.STATUS_SUCCESS -> state = State.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED ->
                    state = release?.let { State.Available(it) } ?: State.Idle
                else -> state = State.Failed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Android did not install it: $it" }
                        ?: "Android did not install it.",
                    release,
                )
            }
        }
    }
}
