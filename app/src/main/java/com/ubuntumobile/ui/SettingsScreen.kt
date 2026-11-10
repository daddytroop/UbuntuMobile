package com.ubuntumobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import com.ubuntumobile.ui.theme.UbuntuPalette

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

/** The app's own settings, outside the Linux desktop. */
@Composable
fun SettingsScreen(
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
    versionName: String,
    ubuntuVersion: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(UbuntuPalette.LightBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(UbuntuPalette.Aubergine)
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card("Your Linux", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Apps, look and app stores", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                        Text(
                            "Go through setup again: apps, light or dark, wallpaper, developer tools, Flathub",
                            fontSize = 12.sp,
                            color = UbuntuPalette.LightOnSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    ActionChip("Set up", onSetUpAgain)
                }
            }

            if (look != null) {
                Card("Look", "Light or dark, the accent colour and the wallpaper - for Linux and for this app.") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Match my phone", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                            Text(
                                "Dark mode and the colour follow Android's. Picking one below turns this off.",
                                fontSize = 12.sp,
                                color = UbuntuPalette.LightOnSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.Switch(
                            checked = matchPhone,
                            onCheckedChange = onMatchPhone,
                            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = UbuntuPalette.UbuntuOrange),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "Light", true to "Dark").forEach { (dark, label) ->
                            val selected = look.dark == dark
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        if (selected) 2.dp else 1.dp,
                                        if (selected) UbuntuPalette.UbuntuOrange else UbuntuPalette.LightOutline,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onLook(look.copy(dark = dark)) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Accent.entries.forEach { accent ->
                            val selected = look.accent == accent
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(accent.color))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) UbuntuPalette.LightOnSurface else Color.Transparent,
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .clickable { onLook(look.copy(accent = accent)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Wallpaper.entries.forEach { w ->
                            val selected = look.wallpaper == w
                            WallpaperThumb(
                                w,
                                Modifier
                                    .size(width = 56.dp, height = 90.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) UbuntuPalette.UbuntuOrange else Color.Transparent,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { onLook(look.copy(wallpaper = w)) },
                            )
                        }
                    }
                }
            }

            Card("Snapshots", "Your whole Linux system - apps, files, settings - as one file, saved wherever you choose: Downloads, Drive, a USB stick.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        snapshotNote ?: "Save one before big changes; restore it to go back.",
                        fontSize = 13.sp,
                        color = UbuntuPalette.LightOnSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!snapshotBusy) {
                        ActionChip("Save snapshot", onBackup)
                        ActionChip("Restore", onRestore)
                    } else {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = UbuntuPalette.UbuntuOrange,
                            strokeWidth = 2.5.dp,
                        )
                    }
                }
            }

            Card("Desktop size", "How big the Linux desktop is drawn on the phone. A monitor always gets a normal desktop.") {
                DesktopSize.entries.forEach { size ->
                    val selected = size == desktopSize
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) UbuntuPalette.UbuntuOrange else UbuntuPalette.LightOutline,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onDesktopSize(size) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(size.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                            Text(size.detail, fontSize = 12.sp, color = UbuntuPalette.LightOnSurfaceVariant)
                        }
                        if (selected) {
                            Text("✓", color = UbuntuPalette.UbuntuOrange, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Card("Screen", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full screen", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                        Text(
                            "Hide the status and navigation bars on the desktop. Swipe from an edge to see them.",
                            fontSize = 12.sp,
                            color = UbuntuPalette.LightOnSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = fullScreen,
                        onCheckedChange = onFullScreen,
                        colors = SwitchDefaults.colors(checkedTrackColor = UbuntuPalette.UbuntuOrange),
                    )
                }
            }

            Card("Phone folders", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Downloads, Documents, Pictures",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = UbuntuPalette.LightOnSurface,
                        )
                        Text(
                            if (storageAllowed) {
                                "In the Linux home folder, shared with your phone's apps"
                            } else {
                                "Let Linux apps open and save files in your phone's folders"
                            },
                            fontSize = 12.sp,
                            color = UbuntuPalette.LightOnSurfaceVariant,
                        )
                    }
                    if (!storageAllowed) {
                        ActionChip("Allow", onAllowStorage)
                    }
                }
            }

            Card("Web browser", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Firefox", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.LightOnSurface)
                        Text(
                            browserNote ?: when (browserInstalled) {
                                true -> "Installed, and the desktop's default browser"
                                false -> "Not installed - Set up adds it"
                                null -> "Checking…"
                            },
                            fontSize = 12.sp,
                            color = UbuntuPalette.LightOnSurfaceVariant,
                        )
                    }
                    if (browserBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = UbuntuPalette.UbuntuOrange,
                        )
                    } else if (browserInstalled == false) {
                        ActionChip("Install", onInstallBrowser)
                    }
                }
            }

            Card("About", null) {
                AboutLine("Mobuntu", versionName)
                AboutLine("Linux", ubuntuVersion)
                AboutLine("Desktop", "XFCE on Xvnc")
            }
        }
    }
}

@Composable
private fun Card(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbuntuPalette.LightSurface)
            .padding(16.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UbuntuPalette.UbuntuOrange)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = UbuntuPalette.LightOnSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(UbuntuPalette.UbuntuOrange)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = UbuntuPalette.LightOnSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = UbuntuPalette.LightOnSurface)
    }
}
