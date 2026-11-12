package com.ubuntumobile.linux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File

/**
 * The phone's battery and network, for the Linux panel.
 *
 * Linux under proot cannot read the phone's battery or see which network
 * it is on, so the panel's battery and network items (linx-status) read
 * them from a file in the shared /tmp that this keeps up to date: on every
 * battery broadcast and network change, and every 15 seconds for the Wi-Fi
 * and mobile signal (which change without telling anyone). It runs while
 * Linux does, started and stopped with [LinuxService].
 */
class PhoneStatus(private val context: Context) {
    companion object {
        private const val TAG = "PhoneStatus"
        private const val REFRESH_MS = 15_000L
        const val FILE = "linx-phone-status"
    }

    private val file = File(LinuxPaths(context).sharedTmpDir, FILE)
    private val handler = Handler(Looper.getMainLooper())
    private var battery: Intent? = null
    private var running = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                battery = intent
                warnIfLow(intent)
            }
            write()
        }
    }

    /** The lowest warning given since the charger was last in (100: none). */
    private var warnedAt = 100

    /**
     * "Battery low" on the Linux desktop at 15% and 5%, so a long job or
     * unsaved work is not lost when a docked phone runs flat out of sight.
     */
    private fun warnIfLow(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged || level < 0) {
            warnedAt = 100
            return
        }
        val step = when {
            level <= 5 -> 5
            level <= 15 -> 15
            else -> return
        }
        if (step >= warnedAt) return
        warnedAt = step
        PhoneNotifications.toLinux(
            context,
            "Battery",
            if (step == 5) "Battery very low: $level%" else "Battery low: $level%",
            if (step == 5) "Plug the phone in now, or save your work: it will turn off soon."
            else "Plug the phone in soon.",
            urgent = step == 5,
            icon = if (step == 5) "battery-level-0-symbolic" else "battery-level-10-symbolic",
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = post()
        override fun onLost(network: Network) = post()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()

        // A new network brings its own DNS servers (Wi-Fi to mobile data,
        // a VPN coming up): Linux's resolv.conf follows.
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            handler.post {
                try {
                    NetworkSetup.configureDns(context, LinuxPaths(context))
                } catch (e: Exception) {
                    Log.w(TAG, "could not update the DNS servers", e)
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            write()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun post() {
        handler.post { write() }
    }

    fun start() {
        if (running) return
        running = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        battery = context.registerReceiver(batteryReceiver, filter)
        try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "no network callback", e)
        }
        handler.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        try {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        file.delete()
    }

    private fun write() {
        if (!running) return
        val lines = try {
            batteryLines() + networkLines() + listOf("updated=${System.currentTimeMillis() / 1000}")
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the status", e)
            return
        }
        try {
            // Whole or not at all: the panel may read it at any moment.
            val tmp = File(file.parentFile, "$FILE.new")
            tmp.writeText(lines.joinToString("\n", postfix = "\n"))
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write the status", e)
        }
    }

    private fun batteryLines(): List<String> {
        val b = battery ?: return emptyList()
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return emptyList()
        val state = when (b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "discharging"
        }
        val plugged = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            8 -> "dock" // BATTERY_PLUGGED_DOCK, Android 13
            else -> ""
        }
        val health = when (b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "too hot"
            BatteryManager.BATTERY_HEALTH_COLD -> "too cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "worn out, needs replacing"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
            else -> ""
        }
        val tenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val fullIn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
        } else {
            -1
        }
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        return listOfNotNull(
            "battery=${level * 100 / scale}",
            "battery_state=$state",
            "plugged=$plugged",
            "health=$health",
            if (tenths != Int.MIN_VALUE) "temp=%.1f".format(java.util.Locale.ROOT, tenths / 10.0) else null,
            "full_in=${if (fullIn > 0) fullIn / 1000 else -1}",
            "power_saver=${if (saver) 1 else 0}",
        )
    }

    private fun networkLines(): List<String> {
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        // Under a VPN the active network is the VPN's; what carries it is
        // the network underneath, which shows in the other networks.
        val carrier = if (vpn) {
            cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }
                ?.firstOrNull { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        } else {
            caps
        }
        val net = when {
            caps == null -> "none"
            carrier?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            carrier?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            carrier?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            vpn -> "vpn"
            else -> "other"
        }
        val signal = when (net) {
            "wifi" -> wifiSignal()
            "cellular" -> cellSignal()
            else -> -1
        }
        return listOf(
            "net=$net",
            "signal=$signal",
            "vpn=${if (vpn) 1 else 0}",
            "metered=${if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 1 else 0}",
            "validated=${if (caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0}",
            "airplane=${if (airplane) 1 else 0}",
        )
    }

    /** Wi-Fi signal, 0 (none) to 4 (excellent). */
    @Suppress("DEPRECATION")
    private fun wifiSignal(): Int = try {
        val rssi = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
        if (rssi == null || rssi <= -127) -1 else WifiManager.calculateSignalLevel(rssi, 5)
    } catch (_: Exception) {
        -1
    }

    /** Mobile signal, 0 (none) to 4 (excellent). */
    private fun cellSignal(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(TelephonyManager::class.java)?.signalStrength?.level ?: -1
        } else {
            -1
        }
    } catch (_: Exception) {
        -1
    }
}
