package com.ubuntumobile.ui

import androidx.compose.runtime.Immutable

/**
 * A launchable item in the app grid.
 *
 * These represent *Linux* applications running inside the Ubuntu container,
 * plus a few built-in "device" apps that the shell provides itself.
 */
@Immutable
data class AppEntry(
    val id: String,
    val name: String,
    val command: List<String>, // guest command to launch
    val category: Category = Category.LINUX,
    val accent: Long = 0xFFE95420,
    /** One line shown under the name in the launcher. */
    val summary: String = "",
    /** The app's icon, a PNG or SVG path inside the container; null draws a symbol. */
    val iconPath: String? = null,
) {
    enum class Category { TERMINAL, LINUX, SYSTEM, FLATPAK }
}

/**
 * The applications the shell knows about out of the box.
 *
 * Every Linux entry is a program that ships in the rootfs, so it works
 * straight after the first-boot install - except Firefox, which the app
 * installs from Mozilla's repository once the desktop is up.
 */
object AppCatalog {

    /** The phone app's own screens, which are always in the launcher. */
    val device: List<AppEntry> get() = builtin.filter { it.command.isEmpty() }

    /**
     * The launcher's apps from linx-apps.py's lines ("id|name|comment|kind|icon"):
     * everything the desktop's menu has, started the way its entry says.
     */
    fun parseInstalled(output: String): List<AppEntry> = output.lines().mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size < 5 || parts[0].isBlank()) return@mapNotNull null
        AppEntry(
            id = parts[0],
            name = parts[1].ifBlank { parts[0] },
            summary = parts[2],
            command = listOf("/usr/bin/python3", "/usr/local/lib/linx/linx-apps.py", "--run", parts[0]),
            category = when (parts[3]) {
                "flatpak" -> AppEntry.Category.FLATPAK
                "system" -> AppEntry.Category.SYSTEM
                else -> AppEntry.Category.LINUX
            },
            accent = 0xFF77216F,
            iconPath = parts[4].ifBlank { null },
        )
    }

    val builtin: List<AppEntry> = listOf(
        AppEntry(
            id = "terminal",
            name = "Terminal",
            summary = "Bash on the desktop",
            command = listOf("/usr/bin/xfce4-terminal"),
            category = AppEntry.Category.TERMINAL,
            accent = 0xFF3D3846,
        ),
        AppEntry(
            id = "browser",
            name = "Firefox",
            summary = "Browse the web",
            command = listOf("firefox"),
            accent = 0xFFE66000,
        ),
        AppEntry(
            id = "files",
            name = "Files",
            summary = "Browse your home folder",
            command = listOf("/usr/bin/thunar"),
            accent = 0xFF3584E4,
        ),
        AppEntry(
            id = "text-editor",
            name = "Text Editor",
            summary = "Mousepad",
            command = listOf("/usr/bin/mousepad"),
            accent = 0xFF26A269,
        ),
        AppEntry(
            id = "system-info",
            name = "System Monitor",
            summary = "Processes and memory",
            command = listOf("/usr/bin/xfce4-taskmanager"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFFC64600,
        ),
        AppEntry(
            id = "settings",
            name = "Settings",
            summary = "Desktop settings",
            command = listOf("/usr/bin/xfce4-settings-manager"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF5E5C64,
        ),
        AppEntry(
            id = "store",
            name = "Software",
            summary = "Install desktop apps",
            command = emptyList(),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFFE95420,
        ),
        AppEntry(
            id = "gamepad",
            name = "Gamepad",
            summary = "On-screen controller",
            command = emptyList(),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF77216F,
        ),
        AppEntry(
            id = "console",
            name = "Console",
            summary = "A shell without the desktop",
            command = emptyList(),
            category = AppEntry.Category.TERMINAL,
            accent = 0xFF300A24,
        ),
    )
}
