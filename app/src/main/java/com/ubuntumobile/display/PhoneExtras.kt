package com.ubuntumobile.display

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ModeNight
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Requests from Linux that the app answers with the phone: the panel's
 * quick settings, a screenshot, locking the screen, printing. They come
 * through the notification bridge (linx-phone quick|screenshot|lock|print).
 */
object PhoneRequests {
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    fun post(event: String) {
        events.tryEmit(event)
    }
}

/**
 * What the phone adds around the desktop, shared by the phone's screen
 * and a monitor's.
 */
object PhoneExtras {
    /** The quick settings panel is open. */
    var quickShown by mutableStateOf(false)

    /** The desktop is locked until the phone's PIN, pattern or fingerprint. */
    var locked by mutableStateOf(false)

    /** The Linux desktop is in sight (on the phone or a monitor). */
    @Volatile var desktopVisible = false

    /** Night light: a warm tint over the desktop. */
    var nightLight by mutableStateOf(false)
    var nightStrength by mutableFloatStateOf(0.5f)

    /** The desktop's own brightness, 0..1, or -1 for the phone's. */
    var brightness by mutableFloatStateOf(-1f)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        nightLight = prefs.getBoolean("night_light", false)
        nightStrength = prefs.getFloat("night_strength", 0.5f)
        brightness = prefs.getFloat("desktop_brightness", -1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("night_light", nightLight)
            .putFloat("night_strength", nightStrength)
            .putFloat("desktop_brightness", brightness)
            .apply()
    }
}

/** Wi-Fi, Bluetooth and the torch, switched from the desktop. */
object PhoneControls {
    private const val TAG = "PhoneControls"

    fun wifiOn(context: Context): Boolean =
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    /** Android lets an app built for Android 9 switch Wi-Fi; if it will not, its own panel opens. */
    @Suppress("DEPRECATION")
    fun setWifi(context: Context, on: Boolean) {
        val done = try {
            context.applicationContext.getSystemService(WifiManager::class.java)?.setWifiEnabled(on) == true
        } catch (e: Exception) {
            Log.w(TAG, "wifi", e)
            false
        }
        if (!done) openSettings(context, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn(context: Context): Boolean = try {
        adapter(context)?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun setBluetooth(context: Context, on: Boolean) {
        val done = try {
            val a = adapter(context)
            a != null && (if (on) a.enable() else a.disable())
        } catch (e: Exception) {
            Log.w(TAG, "bluetooth", e)
            false
        }
        if (!done) openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    private fun torchCamera(context: Context): String? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hasTorch(context: Context) = torchCamera(context) != null

    fun setTorch(context: Context, on: Boolean) {
        val id = torchCamera(context) ?: return
        try {
            context.getSystemService(CameraManager::class.java)?.setTorchMode(id, on)
        } catch (e: Exception) {
            Log.w(TAG, "torch", e)
        }
    }

    fun openSettings(context: Context, action: String) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "no $action", e)
        }
    }

    /** Save the desktop as it is now to the phone's Pictures/Screenshots. */
    fun saveScreenshot(context: Context, bitmap: Bitmap): Boolean {
        val name = "Mobuntu_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(Date()) + ".png"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            }
            val resolver = context.contentResolver
            val uri = if (Build.VERSION.SDK_INT >= 29) {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Screenshots")
                dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                @Suppress("DEPRECATION")
                values.put(MediaStore.Images.Media.DATA, file.absolutePath)
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                return true
            } ?: return false
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "screenshot", e)
            false
        }
    }

    /** Print a PDF Linux made, with the phone's printers (or save it as a PDF). */
    fun print(activity: android.app.Activity, file: File) {
        val manager = activity.getSystemService(android.print.PrintManager::class.java) ?: return
        val adapter = object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?,
                newAttributes: android.print.PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback,
                extras: android.os.Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = android.print.PrintDocumentInfo.Builder(file.name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback,
            ) {
                try {
                    file.inputStream().use { input ->
                        java.io.FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
                    }
                    callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        manager.print(file.nameWithoutExtension, adapter, null)
    }
}

/** A warm tint over the desktop for the evening (multiplied, so whites turn amber). */
@Composable
fun NightLightOverlay(modifier: Modifier = Modifier) {
    if (!PhoneExtras.nightLight) return
    val s = PhoneExtras.nightStrength.coerceIn(0.1f, 1f)
    // Blue goes down most, green a little: as on Android's own night light.
    val tint = Color(1f, 1f - 0.22f * s, 1f - 0.55f * s)
    Canvas(modifier.fillMaxSize()) {
        drawRect(tint, blendMode = BlendMode.Multiply)
    }
}

/**
 * The lock screen over the desktop: a clock, and the phone's own unlock
 * (PIN, pattern, fingerprint). On a monitor there is no button: it is
 * unlocked on the phone.
 */
@Composable
fun LockOverlay(onMonitor: Boolean, modifier: Modifier = Modifier) {
    if (!PhoneExtras.locked) return
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) PhoneExtras.locked = false
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF14080F))
            // Nothing reaches the desktop through it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(16.dp))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(now), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text(DateFormat.getDateInstance(DateFormat.FULL).format(now), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
            Spacer(Modifier.height(36.dp))
            if (onMonitor) {
                Text("Locked. Unlock on your phone.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            } else {
                Text(
                    "Unlock",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(UbuntuPalette.UbuntuOrange)
                        .clickable {
                            val keyguard = context.getSystemService(KeyguardManager::class.java)
                            @Suppress("DEPRECATION")
                            val intent = if (keyguard?.isDeviceSecure == true) {
                                keyguard.createConfirmDeviceCredentialIntent("Unlock Linux", "Use your phone's screen lock")
                            } else null
                            if (intent != null) unlock.launch(intent) else PhoneExtras.locked = false
                        }
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * Quick settings for the desktop, opened from the panel's network or
 * battery item: the phone's Wi-Fi, Bluetooth and torch, the desktop's
 * brightness and night light, keeping the phone awake, locking.
 */
@Composable
fun BoxScope.QuickSettingsPanel(
    onScreenshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Re-read the phone's switches after each change (they settle in a moment).
    var tick by remember { mutableIntStateOf(0) }
    fun later() {
        Handler(Looper.getMainLooper()).postDelayed({ tick++ }, 900)
    }
    var torch by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(CameraManager::class.java)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torch = enabled
            }
        }
        try {
            cm?.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
        onDispose {
            try {
                cm?.unregisterTorchCallback(callback)
            } catch (_: Exception) {
            }
        }
    }
    if (PhoneExtras.quickShown) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { PhoneExtras.quickShown = false },
        )
    }
    AnimatedVisibility(
        visible = PhoneExtras.quickShown,
        enter = slideInVertically { it / 3 } + fadeIn(),
        exit = slideOutVertically { it / 3 } + fadeOut(),
        modifier = modifier.align(Alignment.BottomEnd),
    ) {
        val wifi = remember(tick) { PhoneControls.wifiOn(context) }
        val bluetooth = remember(tick) { PhoneControls.bluetoothOn(context) }
        val battery = remember(tick) {
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = battery?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        }
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        Column(
            Modifier
                .padding(12.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF2242424))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (level != null) "$level%" + if (charging) " · charging" else "" else "Battery",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { PhoneControls.openSettings(context, Intent.ACTION_POWER_USAGE_SUMMARY) },
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { PhoneExtras.quickShown = false }
                        .padding(4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Icons.Default.Wifi, "Wi-Fi", wifi, Modifier.weight(1f), onLong = {
                    PhoneControls.openSettings(context, Settings.ACTION_WIFI_SETTINGS)
                }) { PhoneControls.setWifi(context, !wifi); later() }
                Tile(Icons.Default.Bluetooth, "Bluetooth", bluetooth, Modifier.weight(1f), onLong = {
                    PhoneControls.openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
                }) { PhoneControls.setBluetooth(context, !bluetooth); later() }
                Tile(Icons.Default.FlashlightOn, "Torch", torch, Modifier.weight(1f)) { PhoneControls.setTorch(context, !torch) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(Icons.Default.ModeNight, "Night light", PhoneExtras.nightLight, Modifier.weight(1f)) {
                    PhoneExtras.nightLight = !PhoneExtras.nightLight
                    PhoneExtras.save(context)
                }
                Tile(Icons.Default.Coffee, "Keep awake", com.ubuntumobile.linux.LinuxService.keepAwake, Modifier.weight(1f)) {
                    com.ubuntumobile.linux.LinuxService.toggleAwake(context)
                    later()
                }
                Tile(Icons.Default.Screenshot, "Screenshot", false, Modifier.weight(1f)) {
                    PhoneExtras.quickShown = false
                    onScreenshot()
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessLow, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                Slider(
                    value = if (PhoneExtras.brightness < 0) systemBrightness(context) else PhoneExtras.brightness,
                    onValueChange = { PhoneExtras.brightness = it.coerceIn(0.02f, 1f) },
                    onValueChangeFinished = { PhoneExtras.save(context) },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = UbuntuPalette.UbuntuOrange, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
            if (PhoneExtras.nightLight) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Warmth", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.width(54.dp))
                    Slider(
                        value = PhoneExtras.nightStrength,
                        onValueChange = { PhoneExtras.nightStrength = it },
                        onValueChangeFinished = { PhoneExtras.save(context) },
                        valueRange = 0.1f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFFA040), inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallLink("Lock") { PhoneExtras.quickShown = false; PhoneExtras.locked = true }
                SmallLink("Internet") {
                    PhoneControls.openSettings(
                        context,
                        if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS,
                    )
                }
                SmallLink("Battery") { PhoneControls.openSettings(context, Intent.ACTION_POWER_USAGE_SUMMARY) }
                if (PhoneExtras.brightness >= 0) {
                    SmallLink("Phone's brightness") { PhoneExtras.brightness = -1f; PhoneExtras.save(context) }
                }
            }
        }
    }
}

private fun systemBrightness(context: Context): Float = try {
    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
} catch (_: Exception) {
    0.5f
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) UbuntuPalette.UbuntuOrange else Color.White.copy(alpha = 0.1f))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun SmallLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
