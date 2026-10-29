package com.ubuntumobile.ui

import androidx.compose.runtime.Immutable

/**
 * A launchable item in the app grid / dash.
 *
 * These represent *Linux* applications running inside the Ubuntu container,
 * plus a few built-in "device" apps that the shell provides itself.
 */
@Immutable
data class AppEntry(
    val id: String,
    val name: String,
    val icon: String,          // emoji or short glyph shown in the tile
    val command: List<String>, // guest command to launch
    val category: Category = Category.LINUX,
    val accent: Long = 0xFFE95420,
) {
    enum class Category { TERMINAL, LINUX, SYSTEM, FLATPAK }
}

/**
 * The applications the shell knows about out of the box.
 *
 * These are all standard Ubuntu 26.04 tools that are present in the shipped
 * rootfs, so they work immediately after the first-boot install.
 */
object AppCatalog {

    val builtin: List<AppEntry> = listOf(
        AppEntry(
            id = "terminal",
            name = "Terminal",
            icon = ">_",
            command = listOf("/bin/bash", "--login", "-i"),
            category = AppEntry.Category.TERMINAL,
            accent = 0xFF300A24,
        ),
        AppEntry(
            id = "files",
            name = "Files",
            icon = "\uD83D\uDCC1",
            command = listOf("/usr/bin/nautilus"),
            category = AppEntry.Category.LINUX,
            accent = 0xFFF6D32D,
        ),
        AppEntry(
            id = "text-editor",
            name = "Text Editor",
            icon = "\uD83D\uDCDD",
            command = listOf("/usr/bin/gedit"),
            category = AppEntry.Category.LINUX,
            accent = 0xFF3584E4,
        ),
        AppEntry(
            id = "web-browser",
            name = "Firefox",
            icon = "\uD83D\uDE11",
            command = listOf("/usr/bin/firefox"),
            category = AppEntry.Category.LINUX,
            accent = 0xFFE95420,
        ),
        AppEntry(
            id = "vscode",
            name = "Code",
            icon = "\uD83D\uDC17",
            command = listOf("/usr/bin/code"),
            category = AppEntry.Category.LINUX,
            accent = 0xFF007ACC,
        ),
        AppEntry(
            id = "settings",
            name = "Settings",
            icon = "\u2699",
            command = listOf("/bin/bash", "-lc", "echo 'System settings are managed from the UbuntuMobile shell.'"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF77767B,
        ),
        AppEntry(
            id = "system-info",
            name = "System Monitor",
            icon = "\uD83D\uDCEC",
            command = listOf("/usr/bin/gnome-system-monitor"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF26A269,
        ),
        AppEntry(
            id = "store",
            name = "Ubuntu Software",
            icon = "\uD83D\uDCE6",
            command = listOf("/bin/bash", "-lc", "echo 'Browse Flathub from the Ubuntu Software panel.'"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF77216F,
        ),
        AppEntry(
            id = "gamepad",
            name = "Gamepad",
            icon = "\uD83C\uDFAE",
            command = listOf("/bin/bash", "-lc", "echo 'Virtual controller'"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF2D7FF9,
        ),
        AppEntry(
            id = "display",
            name = "Linux Display",
            icon = "\uD83D\uDCA5",
            command = listOf("/bin/bash", "-lc", "echo 'X11 display'"),
            category = AppEntry.Category.SYSTEM,
            accent = 0xFF26A269,
        ),
    )

    /** Apps surfaced on the dash by default. */
    val dashFavorites: List<String> = listOf(
        "terminal", "files", "text-editor", "web-browser", "display", "store", "gamepad",
    )
}
