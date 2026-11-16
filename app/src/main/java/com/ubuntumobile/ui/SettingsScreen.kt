package com.ubuntumobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * How big the desktop is drawn on the phone. The X screen's short side is
 * this many pixels, with everything doubled; more pixels means smaller
 * text and windows, and more room.
 */
enum class DesktopSize(val label: String, val detail: String, val shortSide: Int) {
    COMPACT("Compact", "Most room; small text", 1080),
    STANDARD("Standard", "Sized for a 6-inch phone", 900),
    LARGE("Large", "Biggest text and buttons", 720),
}

/**
 * The app's own settings, outside the Linux desktop: what needs attention
 * first, then how it looks, how it sits with the phone, and Linux itself.
 */
@Composable
fun SettingsScreen(
    performance: Performance,
    recommendedPerformance: Performance,
    /** The phone's memory, to say why that one is recommended. */
    memoryGb: Int,
    onPerformance: (Performance) -> Unit,
    desktopSize: DesktopSize,
    onDesktopSize: (DesktopSize) -> Unit,
    fullScreen: Boolean,
    onFullScreen: (Boolean) -> Unit,
    /** Firefox's state: null while unknown. */
    browserInstalled: Boolean?,
    browserBusy: Boolean,
    browserNote: String?,
    onInstallBrowser: () -> Unit,
    /** Whether Linux can see the phone's folders. */
    storageAllowed: Boolean,
    onAllowStorage: () -> Unit,
    /** Run onboarding again: apps, look and app stores. */
    onSetUpAgain: () -> Unit,
    /** The look chosen at setup, changed here at once: dark, accent, wallpaper. */
    look: SetupChoices? = null,
    onLook: (SetupChoices) -> Unit = {},
    /** Dark mode and the accent follow the phone's. */
    matchPhone: Boolean = false,
    onMatchPhone: (Boolean) -> Unit = {},
    /** A snapshot being saved or restored ("Saving: 820 MB"), or how the last one went. */
    snapshotNote: String? = null,
    snapshotBusy: Boolean = false,
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    /** Android may kill Linux's processes (BackgroundLimits); null hides the card. */
    childProcessesLimited: Boolean? = null,
    canTurnOffChildLimits: Boolean = true,
    batteryRestricted: Boolean = false,
    onOpenDeveloperOptions: () -> Unit = {},
    onAskBattery: () -> Unit = {},
    /** Mobuntu is the phone's home screen. */
    isHomeApp: Boolean = false,
    onHomeApp: (Boolean) -> Unit = {},
    /** Where weekly snapshots go, or null when they are off. */
    weeklyFolder: String? = null,
    onWeekly: (Boolean) -> Unit = {},
    versionName: String,
    ubuntuVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MobuntuScreen(title = "Settings", onBack = onClose, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .topFade()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ui.gutter)
                .padding(top = 6.dp, bottom = 28.dp)
                .widthIn(max = Ui.maxWidth)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Hero(versionName, ubuntuVersion)

            // What stops Linux from running well comes first, and only
            // while there is something to do about it.
            val needsCare = childProcessesLimited == true || batteryRestricted
            if (childProcessesLimited != null && needsCare) {
                KeepRunning(childProcessesLimited, canTurnOffChildLimits, batteryRestricted, onOpenDeveloperOptions, onAskBattery)
            }

            if (look != null) {
                Group("Appearance") {
                    SettingRow(
                        Icons.Default.AutoAwesome,
                        "Match my phone",
                        "Dark mode and the colour follow Android's. Picking one below turns this off.",
                    ) { AccentSwitch(matchPhone, onMatchPhone) }
                    RowDivider()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label(Icons.Default.Palette, "Linux's look")
                        Segmented(listOf(false, true), look.dark, { if (it) "Dark" else "Light" }, { onLook(look.copy(dark = it)) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Accent.entries.forEach { accent ->
                                val selected = look.accent == accent
                                val grow by animateFloatAsState(if (selected) 1.15f else 1f, spring(dampingRatio = 0.45f), label = "accent")
                                Box(
                                    Modifier
                                        .graphicsLayer { scaleX = grow; scaleY = grow }
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(accent.color))
                                        .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                                        .clickable { onLook(look.copy(accent = accent)) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(Icons.Default.Check, contentDescription = accent.label, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Wallpaper.entries.forEach { w ->
                                val selected = look.wallpaper == w
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    WallpaperThumb(
                                        w,
                                        Modifier
                                            .size(width = 62.dp, height = 100.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(
                                                if (selected) 3.dp else 1.dp,
                                                if (selected) Color.White else Ui.edge,
                                                RoundedCornerShape(14.dp),
                                            )
                                            .clickable { onLook(look.copy(wallpaper = w)) },
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(w.label, color = if (selected) Ui.ink else Ui.inkFaint, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    RowDivider()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(Icons.Default.AspectRatio, "Size on the phone")
                        Segmented(DesktopSize.entries, desktopSize, { it.label }, onDesktopSize)
                        Text(
                            desktopSize.detail + ". A monitor always gets a full-size desktop.",
                            color = Ui.inkSoft,
                            fontSize = 12.5.sp,
                        )
                    }
                    RowDivider()
                    SettingRow(
                        Icons.Default.Fullscreen,
                        "Full screen",
                        "Hide the status and navigation bars on the desktop. Swipe from an edge to see them.",
                    ) { AccentSwitch(fullScreen, onFullScreen) }
                }
            }

            Group("Performance") {
                Text(
                    "Pick the one for your phone. You can switch any time; it changes at once.",
                    color = Ui.inkSoft,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                )
                Performance.entries.forEachIndexed { i, level ->
                    if (i > 0) RowDivider()
                    PerformanceRow(level, level == performance, level == recommendedPerformance, memoryGb) { onPerformance(level) }
                }
            }

            Group("With your phone") {
                PhoneNotificationsRow()
                RowDivider()
                SettingRow(
                    Icons.Default.Home,
                    "Use Mobuntu as the home screen",
                    "Home opens your phone apps and Linux apps in one place, with the Linux desktop a tap away.",
                    tint = Color(0xFF8B5CF6),
                ) { AccentSwitch(isHomeApp, onHomeApp) }
                RowDivider()
                SettingRow(
                    Icons.Default.Folder,
                    "Phone folders",
                    if (storageAllowed) "Downloads, Documents and Pictures are in the Linux home folder, shared with your phone's apps."
                    else "Let Linux apps open and save files in Downloads, Documents and Pictures.",
                    tint = Color(0xFFF59E0B),
                ) {
                    if (storageAllowed) Done() else GlassButton("Allow", onAllowStorage)
                }
            }

            Group("Linux") {
                SettingRow(
                    Icons.Default.AutoAwesome,
                    "Apps, look and app stores",
                    "Go through setup again: what it is for, apps, developer tools, Flathub.",
                    tint = Color(0xFFEC4899),
                ) { GlassButton("Set up", onSetUpAgain) }
                RowDivider()
                SettingRow(
                    Icons.Default.Public,
                    "Firefox",
                    browserNote ?: when (browserInstalled) {
                        true -> "Installed, and the desktop's default browser."
                        false -> "Not installed."
                        null -> "Checking…"
                    },
                    tint = Color(0xFFF97316),
                ) {
                    when {
                        browserBusy -> Busy()
                        browserInstalled == false -> GlassButton("Install", onInstallBrowser)
                        browserInstalled == true -> Done()
                    }
                }
                RowDivider()
                SettingRow(
                    Icons.Default.Save,
                    "Snapshots",
                    snapshotNote
                        ?: "Your whole Linux - apps, files, settings - as one file, saved wherever you choose. Save one before big changes.",
                    tint = Color(0xFF10B981),
                )
                Row(
                    Modifier.padding(start = 66.dp, end = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (snapshotBusy) {
                        Busy()
                    } else {
                        GlassButton("Save snapshot", onBackup)
                        GlassButton("Restore", onRestore, primary = false)
                    }
                }
                RowDivider()
                SettingRow(
                    Icons.Default.Schedule,
                    "A snapshot every week",
                    weeklyFolder?.let { "Into $it; the last two are kept." }
                        ?: "Saved by itself, into a folder you choose.",
                    tint = Color(0xFF10B981),
                ) { AccentSwitch(weeklyFolder != null, onWeekly) }
            }

            if (childProcessesLimited != null && !needsCare) {
                KeepRunning(childProcessesLimited, canTurnOffChildLimits, batteryRestricted, onOpenDeveloperOptions, onAskBattery)
            }

            Group("About") {
                AboutLine("Mobuntu", versionName)
                RowDivider()
                AboutLine("Linux", ubuntuVersion.ifBlank { "Ubuntu" })
                RowDivider()
                AboutLine("Desktop", "XFCE on Xvnc")
            }
        }
    }
}

/** The app at a glance, at the top. */
@Composable
private fun Hero(versionName: String, ubuntuVersion: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Ui.cardShape)
            .background(Brush.linearGradient(listOf(Ui.accent.copy(alpha = 0.42f), Ui.card)))
            .border(1.dp, Ui.edge, Ui.cardShape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UbuntuLogo(54.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Mobuntu", color = Ui.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                listOf(ubuntuVersion.ifBlank { "Ubuntu" }, "version $versionName").joinToString(" · "),
                color = Ui.inkSoft,
                fontSize = 13.sp,
            )
        }
    }
}

/** Android's two ways of stopping Linux, and whether each is out of the way. */
@Composable
private fun KeepRunning(
    childProcessesLimited: Boolean,
    canTurnOffChildLimits: Boolean,
    batteryRestricted: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onAskBattery: () -> Unit,
) {
    Group("Keep Linux running") {
        LimitRow(
            icon = Icons.Default.Memory,
            title = "Child process restrictions",
            ok = !childProcessesLimited,
            okText = "Off: Android leaves Linux's programs alone.",
            fixText = if (canTurnOffChildLimits) {
                "On: Android may close Linux programs at random. In Developer options, search for \"child\" and turn on \"Disable child process restrictions\". " +
                    "No Developer options? Settings → About phone → Software information → tap Build number 7 times."
            } else {
                "This Android version limits how many programs an app may run; it has no switch for it."
            },
            action = if (canTurnOffChildLimits) "Open" else null,
            onAction = onOpenDeveloperOptions,
        )
        RowDivider()
        LimitRow(
            icon = Icons.Default.BatteryChargingFull,
            title = "Battery optimisation",
            ok = !batteryRestricted,
            okText = "Off for Mobuntu: it may run in the background.",
            fixText = "On: Android may stop Mobuntu, and Linux with it, to save battery.",
            action = "Allow",
            onAction = onAskBattery,
        )
    }
}

@Composable
private fun LimitRow(icon: ImageVector, title: String, ok: Boolean, okText: String, fixText: String, action: String?, onAction: () -> Unit) {
    SettingRow(icon, title, if (ok) okText else fixText, tint = if (ok) Color(0xFF16A34A) else Color(0xFFD97706)) {
        when {
            ok -> Done()
            action != null -> GlassButton(action, onAction)
            else -> Icon(Icons.Default.Warning, contentDescription = "Needs attention", tint = Ui.warn, modifier = Modifier.size(22.dp))
        }
    }
}

/** Phone notifications on the desktop: read afresh when coming back from Android's settings. */
@Composable
private fun PhoneNotificationsRow() {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed++
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    val granted = remember(resumed) { com.ubuntumobile.linux.PhoneNotifications.granted(context) }
    var on by remember { mutableStateOf(com.ubuntumobile.linux.PhoneNotifications.enabled(context)) }
    SettingRow(
        Icons.Default.Notifications,
        "Phone notifications on the desktop",
        if (granted) "Messages pop up on the desktop and wait in the panel's bell. The phone still gets them too."
        else "Android asks you to allow Mobuntu to read notifications first.",
        tint = Color(0xFFEF4444),
    ) {
        AccentSwitch(on && granted) { want ->
            on = want
            com.ubuntumobile.linux.PhoneNotifications.setEnabled(context, want)
            if (want && !granted) com.ubuntumobile.linux.PhoneNotifications.askForAccess(context)
        }
    }
}

/** A small heading inside a card, for a block of controls. */
@Composable
private fun Label(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Ui.inkSoft, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Ui.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Done() {
    Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = Ui.good, modifier = Modifier.size(24.dp))
}

@Composable
private fun Busy() {
    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Ui.accent, trackColor = Ui.edge, strokeWidth = 2.5.dp)
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Text(label, fontSize = 14.5.sp, color = Ui.inkSoft, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.5.sp, color = Ui.ink, fontWeight = FontWeight.Medium)
    }
}

/** One performance level: what phones it is for, what it does, and a tick. */
@Composable
private fun PerformanceRow(level: Performance, selected: Boolean, recommended: Boolean, memoryGb: Int, onClick: () -> Unit) {
    val icon = when (level) {
        Performance.BASIC -> Icons.Default.BatterySaver
        Performance.BALANCED -> Icons.Default.Tune
        Performance.FAST -> Icons.Default.Bolt
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Ui.accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, if (selected) Ui.accent else Color(0xFF64748B))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(level.label, color = Ui.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(" · " + level.phones, color = Ui.inkSoft, fontSize = 13.sp)
            }
            if (recommended) {
                Text(
                    "Recommended for this phone ($memoryGb GB)",
                    color = Ui.good,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(level.detail, color = Ui.inkSoft, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Ui.accent else Ui.edge, CircleShape)
                .background(if (selected) Ui.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}
