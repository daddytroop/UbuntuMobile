package com.ubuntumobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What someone wants the phone's Linux for. Setup asks ("What will you
 * use it for?") and suggests apps for each; the store shows the same
 * collections on its front page, the chosen ones first.
 */
enum class Interest(
    val id: String,
    val label: String,
    val detail: String,
    val icon: ImageVector,
    val color: Long,
    /** Offered in setup; the rest only appear in the store. */
    val inSetup: Boolean = true,
) {
    WORK("work", "Work and school", "Documents, email, notes and studying", Icons.Default.Work, 0xFF2A6CB5),
    GAMING("gaming", "Gaming", "Emulators for classic consoles, and games to play", Icons.Default.SportsEsports, 0xFF7B3FA0),
    MEDIA("media", "Music and video", "Players, music, radio and streaming", Icons.Default.Movie, 0xFFD38B00),
    CREATIVE("creative", "Creating", "Photos, drawing, video and audio editing", Icons.Default.Brush, 0xFFE95420),
    SOCIAL("social", "Chat and social", "Messaging apps with a desktop version", Icons.Default.Forum, 0xFF1E88C7),
    CODING("coding", "Coding", "Code editors, Git and developer tools", Icons.Default.Code, 0xFF24292F),
    WEB("web", "Other browsers", "Chrome, Brave and more, next to Firefox", Icons.Default.Public, 0xFF0A84FF, inSetup = false),
    TOOLS("tools", "Handy tools", "Send files, see what is running, and more", Icons.Default.Build, 0xFF3D8B5A, inSetup = false),
    ;

    /** A note shown with the collection, when it needs one. */
    val note: String?
        get() = when (this) {
            GAMING ->
                "Emulators run without the phone's graphics chip for now: consoles up to the PlayStation and PSP " +
                    "play well, 3D consoles after them are slow. Bring your own games."
            else -> null
        }

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun parse(text: String?): Set<Interest> =
            text.orEmpty().split(' ', ',').mapNotNull { byId(it.trim()) }.toSet()
    }
}

/** One app in a collection: from Flathub, or one of the store's Ubuntu apps. */
data class Curated(
    /** Flathub app id, or the Ubuntu package of a [StoreCatalog] app. */
    val id: String,
    val name: String,
    val summary: String,
    val interest: Interest,
    /** Ticked in setup when its interest is picked. */
    val pick: Boolean = false,
    /** Flathub builds it only for PCs (x86-64). */
    val pcOnly: Boolean = false,
    /** Flathub builds it only for ARM phones (Betterbird stands in for Thunderbird). */
    val armOnly: Boolean = false,
) {
    /** One of the store's Ubuntu apps rather than a Flatpak. */
    val ubuntu: StoreApp? get() = StoreCatalog.featured.firstOrNull { it.pkg == id }

    val runsHere: Boolean
        get() = when (FlathubApi.deviceArch) {
            "x86_64" -> !armOnly
            "aarch64" -> !pcOnly
            null -> ubuntu != null
            else -> !pcOnly
        }

    fun toStoreApp(): StoreApp = ubuntu ?: StoreApp(
        pkg = id, name = name, summary = summary, command = listOf("/usr/bin/flatpak", "run", id),
        installer = flatpakInstaller(id),
        icon = interest.icon, accent = interest.color, flatpak = true,
        iconUrl = "https://dl.flathub.org/repo/appstream/x86_64/icons/128x128/$id.png",
        available = runsHere,
    )
}

object Collections {
    private fun c(id: String, name: String, summary: String, interest: Interest, pick: Boolean = false, pcOnly: Boolean = false, armOnly: Boolean = false) =
        Curated(id, name, summary, interest, pick, pcOnly, armOnly)

    val all: List<Curated> = listOf(
        // Work and school
        c("libreoffice-writer", "LibreOffice Writer", "Documents, like Word", Interest.WORK, pick = true),
        c("libreoffice-calc", "LibreOffice Calc", "Spreadsheets, like Excel", Interest.WORK, pick = true),
        c("libreoffice-impress", "LibreOffice Impress", "Presentations, like PowerPoint", Interest.WORK),
        c("org.mozilla.Thunderbird", "Thunderbird", "Email and calendar", Interest.WORK, pick = true, pcOnly = true),
        c("eu.betterbird.Betterbird", "Betterbird", "Thunderbird's email and calendar, built for phones", Interest.WORK, pick = true, armOnly = true),
        c("evince", "Document Viewer", "PDFs and documents", Interest.WORK, pick = true),
        c("org.onlyoffice.desktopeditors", "ONLYOFFICE", "Office documents that look like Microsoft's", Interest.WORK),
        c("md.obsidian.Obsidian", "Obsidian", "Notes that link together", Interest.WORK),
        c("net.cozic.joplin_desktop", "Joplin", "Notes and to-dos, synced", Interest.WORK),
        c("com.github.xournalpp.xournalpp", "Xournal++", "Handwritten notes and marking up PDFs", Interest.WORK),
        c("net.ankiweb.Anki", "Anki", "Flashcards for studying", Interest.WORK),
        c("keepassxc", "KeePassXC", "Password manager", Interest.WORK),

        // Gaming
        c("org.libretro.RetroArch", "RetroArch", "Every classic console in one app", Interest.GAMING, pick = true),
        c("org.ppsspp.PPSSPP", "PPSSPP", "PlayStation Portable", Interest.GAMING, pick = true),
        c("org.duckstation.DuckStation", "DuckStation", "PlayStation", Interest.GAMING),
        c("io.mgba.mGBA", "mGBA", "Game Boy and Game Boy Advance", Interest.GAMING),
        c("com.snes9x.Snes9x", "Snes9x", "Super Nintendo", Interest.GAMING),
        c("com.github.Rosalie241.RMG", "Rosalie's Mupen GUI", "Nintendo 64", Interest.GAMING),
        c("net.kuribo64.melonDS", "melonDS", "Nintendo DS", Interest.GAMING),
        c("org.azahar_emu.Azahar", "Azahar", "Nintendo 3DS", Interest.GAMING),
        c("org.DolphinEmu.dolphin-emu", "Dolphin", "GameCube and Wii", Interest.GAMING),
        c("org.flycast.Flycast", "Flycast", "Dreamcast", Interest.GAMING),
        c("org.mamedev.MAME", "MAME", "Arcade machines", Interest.GAMING),
        c("org.scummvm.ScummVM", "ScummVM", "Classic point-and-click adventures", Interest.GAMING),
        c("com.moonlight_stream.Moonlight", "Moonlight", "Stream games from your gaming PC", Interest.GAMING, pick = true),
        c("net.minetest.Minetest", "Luanti", "A block-building world, free", Interest.GAMING),
        c("net.supertuxkart.SuperTuxKart", "SuperTuxKart", "Kart racing", Interest.GAMING),
        c("org.prismlauncher.PrismLauncher", "Prism Launcher", "Minecraft: Java Edition", Interest.GAMING),
        c("com.valvesoftware.Steam", "Steam", "Your PC games library", Interest.GAMING, pcOnly = true),

        // Music and video
        c("vlc", "VLC", "Plays any video", Interest.MEDIA, pick = true),
        c("io.github.celluloid_player.Celluloid", "Celluloid", "A simple, good-looking video player", Interest.MEDIA),
        c("io.mpv.Mpv", "mpv", "A light video player", Interest.MEDIA),
        c("org.gnome.Rhythmbox3", "Rhythmbox", "Your music library", Interest.MEDIA, pick = true),
        c("de.haeckerfelix.Shortwave", "Shortwave", "Internet radio stations", Interest.MEDIA),
        c("io.freetubeapp.FreeTube", "FreeTube", "YouTube without ads or tracking", Interest.MEDIA),
        c("com.github.iwalton3.jellyfin-media-player", "Jellyfin Media Player", "Your own media server's films and shows", Interest.MEDIA),
        c("com.github.unrud.VideoDownloader", "Video Downloader", "Save videos from websites", Interest.MEDIA),
        c("com.spotify.Client", "Spotify", "Music streaming", Interest.MEDIA, pcOnly = true),

        // Creating
        c("gimp", "GIMP", "Photo editing", Interest.CREATIVE, pick = true),
        c("org.kde.krita", "Krita", "Digital painting", Interest.CREATIVE, pick = true),
        c("inkscape", "Inkscape", "Vector drawing", Interest.CREATIVE),
        c("com.github.PintaProject.Pinta", "Pinta", "Simple image editing, like Paint.NET", Interest.CREATIVE),
        c("org.darktable.Darktable", "darktable", "Photo developing for RAW files", Interest.CREATIVE),
        c("org.kde.kdenlive", "Kdenlive", "Video editing", Interest.CREATIVE),
        c("org.audacityteam.Audacity", "Audacity", "Record and edit audio", Interest.CREATIVE),
        c("org.blender.Blender", "Blender", "3D modelling and animation", Interest.CREATIVE, pcOnly = true),
        c("com.obsproject.Studio", "OBS Studio", "Recording and streaming", Interest.CREATIVE, pcOnly = true),

        // Chat and social
        c("org.telegram.desktop", "Telegram", "Messaging", Interest.SOCIAL, pick = true),
        c("org.signal.Signal", "Signal", "Private messaging, linked to your phone", Interest.SOCIAL),
        c("com.rtosta.zapzap", "ZapZap", "WhatsApp on the desktop", Interest.SOCIAL, pick = true),
        c("dev.vencord.Vesktop", "Vesktop", "Discord, built for phones too", Interest.SOCIAL, armOnly = true),
        c("com.discordapp.Discord", "Discord", "Voice, video and text chat", Interest.SOCIAL, pcOnly = true),
        c("im.riot.Riot", "Element", "Matrix chat", Interest.SOCIAL),
        c("com.slack.Slack", "Slack", "Work chat", Interest.SOCIAL, pcOnly = true),

        // Coding
        c("com.visualstudio.code", "Visual Studio Code", "Microsoft's code editor", Interest.CODING, pick = true),
        c("geany", "Geany", "A light code editor", Interest.CODING),
        c("io.github.shiftey.Desktop", "GitHub Desktop", "Git, made simple", Interest.CODING),
        c("org.godotengine.Godot", "Godot", "Make your own games", Interest.CODING),
        c("org.gnome.Builder", "Builder", "GNOME's IDE", Interest.CODING),
        c("com.jetbrains.IntelliJ-IDEA-Community", "IntelliJ IDEA", "Java and Kotlin IDE", Interest.CODING),

        // Other browsers
        c("com.google.Chrome", "Google Chrome", "Google's browser", Interest.WEB),
        c("org.chromium.Chromium", "Chromium", "Chrome's open-source base", Interest.WEB),
        c("com.brave.Browser", "Brave", "Blocks ads and trackers", Interest.WEB),
        c("io.gitlab.librewolf-community", "LibreWolf", "Firefox, tuned for privacy", Interest.WEB),

        // Handy tools
        c("org.localsend.localsend_app", "LocalSend", "Send files to nearby devices, like AirDrop", Interest.TOOLS),
        c("io.missioncenter.MissionCenter", "Mission Center", "See what is using the processor and memory", Interest.TOOLS),
        c("com.bitwarden.desktop", "Bitwarden", "Password manager, synced", Interest.TOOLS),
        c("org.qbittorrent.qBittorrent", "qBittorrent", "Torrent downloads", Interest.TOOLS),
        c("com.github.tchx84.Flatseal", "Flatseal", "Flatpak app permissions", Interest.TOOLS),
    )

    /** A collection's apps, those that run on this phone first. */
    fun of(interest: Interest): List<Curated> =
        // A stand-in for another processor (Betterbird on a PC) is left out;
        // a PC-only app stays, marked, so its stand-in can be explained.
        all.filter { it.interest == interest && (it.runsHere || !it.armOnly) }
            .sortedBy { if (it.runsHere) 0 else 1 }

    /** The apps setup ticks for these interests (only those that run here). */
    fun picks(interests: Set<Interest>): List<Curated> =
        all.filter { it.interest in interests && it.pick && it.runsHere }
}
