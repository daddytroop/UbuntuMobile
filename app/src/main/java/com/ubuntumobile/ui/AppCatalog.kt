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
            name = "Ubuntu Software",
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
