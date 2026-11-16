package com.ubuntumobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ubuntumobile.linux.BackgroundLimits
import com.ubuntumobile.linux.NotificationBridge
import com.ubuntumobile.ui.theme.UbuntuPalette

/** One of the phone settings the "one device" page walks through. */
private data class PhoneStep(
    val title: String,
    val why: String,
    /** Done; null when this phone has nothing to set. */
    val done: Boolean?,
    val action: String,
    val onAction: () -> Unit,
)

/**
 * Onboarding's "one device" page: what the phone can be with Linux on it,
 * then the phone settings that make it work, each a tap away, ticked off as
 * they are done. The page reads them again whenever the user comes back
 * from Android's settings.
 */
@Composable
internal fun OneDevicePage(onRemaining: (Int) -> Unit = {}) {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) checks++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }

    val steps = remember(checks) {
        buildList {
            val notifying = NotificationManagerCompat.from(context).areNotificationsEnabled()
            add(
                PhoneStep(
                    "Notifications",
                    "Messages from Linux apps, like Telegram, show up on your phone.",
                    notifying,
                    "Allow",
                ) {
                    // Android asks the first time the app makes a notification channel.
                    NotificationBridge.ensureChannel(context)
                    checks++
                    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return@PhoneStep
                    open(
                        context,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
            add(
                PhoneStep(
                    "Your phone's files",
                    "Downloads, Photos and Documents open right in Linux apps.",
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED,
                    "Allow",
                ) {
                    storage.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                },
            )
            add(
                PhoneStep(
                    "Run in the background",
                    "Linux keeps going while you use other apps, instead of Android stopping it to save battery.",
                    !BackgroundLimits.batteryRestricted(context),
                    "Allow",
                ) { BackgroundLimits.askForBattery(context) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val limited = BackgroundLimits.childProcessesLimited(context)
                val developer = developerOptionsOn(context)
                add(
                    when {
                        !BackgroundLimits.canTurnOffInSettings -> PhoneStep(
                            "Child process limit",
                            "This Android version limits how many programs an app may run and has no switch for it. " +
                                "Now and then Linux may close a program; reopen it from the menu.",
                            null, "", {},
                        )
                        !limited -> PhoneStep("Child process restrictions", "Off: Android leaves Linux's programs alone.", true, "", {})
                        !developer -> PhoneStep(
                            "Child process restrictions · step 1 of 2",
                            "Android closes Linux programs at random unless this is off, and the switch is in Developer options. " +
                                "To show them: open About phone → Software information, then tap Build number 7 times.",
                            false, "Open About phone",
                        ) { open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
                        else -> PhoneStep(
                            "Child process restrictions · step 2 of 2",
                            "In Developer options, tap the search icon, type \"child\", open \"Disable child process restrictions\" and turn it on.",
                            false, "Open Developer options",
                        ) { BackgroundLimits.openDeveloperOptions(context) }
                    },
                )
            }
        }
    }

    // The settings come first: on a small screen, what is below the fold
    // is skipped, and without them Android closes Linux's apps.
    val needed = steps.filter { it.done != null }
    val remaining = needed.count { it.done == false }
    androidx.compose.runtime.LaunchedEffect(remaining) { onRemaining(remaining) }
    PageTitle(
        "Phone settings",
        if (remaining == 0) "All set: Linux can keep running and reach your things."
        else "Without these, Android closes Linux apps. Each is a tap; come back here after each one.",
    )
    StepsLabel("${needed.count { it.done == true }} of ${needed.size} done")
    Spacer(Modifier.height(6.dp))
    steps.forEachIndexed { i, step -> StepRow(i + 1, step) }

    // Optional, and not counted: some keep their usual home screen.
    val activity = context as? android.app.Activity
    val home = remember(checks) { HomeScreen.isDefault(context) }
    Spacer(Modifier.height(10.dp))
    StepsLabel("Optional")
    StepRow(
        steps.size + 1,
        PhoneStep(
            "Make it your home screen",
            if (home) "Home opens your phone and Linux apps in one place. Settings → Home screen turns it off."
            else "For when the phone is your only computer: Home opens your phone and Linux apps in one place, with the desktop a tap away.",
            home,
            "Use as home screen",
        ) { activity?.let { HomeScreen.ask(it) } },
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Settings → Keep Linux running has these too. The microphone is asked for the first time a Linux app needs it.",
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
    )

    Spacer(Modifier.height(22.dp))
    StepsLabel("One device for everything")
    Feature(Icons.Default.DesktopWindows, "Dock it", "Plug into a monitor, keyboard and mouse for a full Ubuntu desktop.")
    Feature(Icons.Default.SyncAlt, "Share between both", "Share files, links and copy-paste between your phone and Linux.")
    Feature(Icons.Default.Apps, "One home for your apps", "Phone apps in the Linux menu; Linux notifications on the phone.")
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(UbuntuPalette.UbuntuOrange.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun StepRow(number: Int, step: PhoneStep) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (step.done == true) 0.05f else 0.1f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (step.done == true) UbuntuPalette.UbuntuOrange else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (step.done == true) {
                Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(18.dp))
            } else {
                Text(if (step.done == null) "i" else "$number", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(step.why, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            if (step.done == false) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(UbuntuPalette.UbuntuOrange)
                        .clickable(onClick = step.onAction)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(step.action, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun developerOptionsOn(context: Context): Boolean = try {
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
} catch (_: Exception) {
    false
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

@Composable
private fun StepsLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
