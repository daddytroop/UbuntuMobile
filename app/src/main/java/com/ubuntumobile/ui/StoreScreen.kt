package com.ubuntumobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An application the store offers. */
data class StoreApp(
    /** The apt package that marks it as installed. */
    val pkg: String,
    val name: String,
    val summary: String,
    /** How to start it on the desktop; empty for search results. */
    val command: List<String> = emptyList(),
    /** Everything to install (defaults to [pkg]). */
    val packages: List<String> = listOf(pkg),
    /** A script that installs it instead of apt-install.sh (Firefox). */
    val installer: String? = null,
    val icon: ImageVector? = null,
    val accent: Long = 0xFFE95420,
    /** From Flathub: [pkg] is the app id. */
    val flatpak: Boolean = false,
)

/** Turns Flathub on if it is not yet (linx-flatpak.sh), then installs. */
private fun flatpakInstaller(id: String) =
    "/usr/local/lib/linx/linx-flatpak.sh >/dev/null && flatpak install -y --noninteractive flathub $id"

private fun flathubApp(id: String, name: String, summary: String, icon: ImageVector, accent: Long) = StoreApp(
    pkg = id, name = name, summary = summary, command = listOf("/usr/bin/flatpak", "run", id),
    installer = flatpakInstaller(id), icon = icon, accent = accent, flatpak = true,
)

/**
 * Flathub's front page: well-known apps built for both 64-bit ARM and
 * x86. Flatpak's sandbox needs kernel features proot does not have, so
 * they run through linx-bwrap, which gives them the same file system
 * without the sandbox.
 */
object FlathubCatalog {
    val featured = listOf(
        flathubApp("org.mozilla.Thunderbird", "Thunderbird", "Email and calendar", Icons.Default.Email, 0xFF0A84FF),
        flathubApp("org.telegram.desktop", "Telegram", "Messaging", Icons.Default.Send, 0xFF2AABEE),
        flathubApp("org.signal.Signal", "Signal", "Private messaging", Icons.Default.Forum, 0xFF3A76F0),
        flathubApp("com.visualstudio.code", "Visual Studio Code", "Code editor", Icons.Default.Code, 0xFF007ACC),
        flathubApp("org.kde.krita", "Krita", "Digital painting", Icons.Default.Brush, 0xFF3BABFF),
        flathubApp("org.blender.Blender", "Blender", "3D modelling and animation", Icons.Default.ViewInAr, 0xFFEA7600),
        flathubApp("com.obsproject.Studio", "OBS Studio", "Recording and streaming", Icons.Default.Videocam, 0xFF302E31),
        flathubApp("io.mpv.Mpv", "mpv", "A light video player", Icons.Default.Movie, 0xFF691F69),
        flathubApp("com.github.tchx84.Flatseal", "Flatseal", "Flatpak app permissions", Icons.Default.Key, 0xFF4A86CF),
    )
}

/** App id prefixes of the big desktop projects, shown first in a search. */
private val WELL_KNOWN = listOf(
    "org.gnome.", "org.kde.", "org.mozilla.", "org.libreoffice.", "org.gimp.", "org.inkscape.",
    "org.videolan.", "org.blender.", "com.obsproject.", "org.freedesktop.",
)

private enum class StoreSource(val label: String) { UBUNTU("Ubuntu"), FLATHUB("Flathub") }

/**
 * The store's front page: well-known desktop applications that run in the
 * container. They are ordinary Ubuntu packages (Firefox comes from Mozilla's
 * own repository, because Ubuntu's is a snap), so they install with apt.
 */
object StoreCatalog {
    val featured = listOf(
        StoreApp(
            pkg = "firefox", name = "Firefox", summary = "The web browser",
            command = listOf("firefox"), installer = "/usr/local/lib/linx/install-firefox.sh",
            icon = Icons.Default.Public, accent = 0xFFE66000,
        ),
        StoreApp(
            pkg = "libreoffice-writer", name = "LibreOffice Writer", summary = "Documents",
            command = listOf("libreoffice", "--writer"),
            packages = listOf("libreoffice-writer", "libreoffice-gtk3"),
            icon = Icons.Default.Description, accent = 0xFF2A6CB5,
        ),
        StoreApp(
            pkg = "libreoffice-calc", name = "LibreOffice Calc", summary = "Spreadsheets",
            command = listOf("libreoffice", "--calc"),
            packages = listOf("libreoffice-calc", "libreoffice-gtk3"),
            icon = Icons.Default.TableChart, accent = 0xFF26A269,
        ),
        StoreApp(
            pkg = "libreoffice-impress", name = "LibreOffice Impress", summary = "Presentations",
            command = listOf("libreoffice", "--impress"),
            packages = listOf("libreoffice-impress", "libreoffice-gtk3"),
            icon = Icons.Default.Slideshow, accent = 0xFFC64600,
        ),
        StoreApp(
            pkg = "gimp", name = "GIMP", summary = "Photo editing",
            command = listOf("gimp"), icon = Icons.Default.Brush, accent = 0xFF5E5C64,
        ),
        StoreApp(
            pkg = "inkscape", name = "Inkscape", summary = "Vector drawing",
            command = listOf("inkscape"), icon = Icons.Default.Draw, accent = 0xFF3D3846,
        ),
        StoreApp(
            pkg = "vlc", name = "VLC", summary = "Video player",
            command = listOf("vlc"), icon = Icons.Default.Movie, accent = 0xFFE5A50A,
        ),
        StoreApp(
            pkg = "geany", name = "Geany", summary = "Code editor",
            command = listOf("geany"), icon = Icons.Default.Code, accent = 0xFF1C71D8,
        ),
        StoreApp(
            pkg = "evince", name = "Document Viewer", summary = "PDF and documents",
            command = listOf("evince"), icon = Icons.Default.PictureAsPdf, accent = 0xFFC01C28,
        ),
        StoreApp(
            pkg = "keepassxc", name = "KeePassXC", summary = "Password manager",
            command = listOf("keepassxc"), icon = Icons.Default.Key, accent = 0xFF33D17A,
        ),
    )
}

/**
 * "Ubuntu Software": install desktop applications into the container.
 *
 * All the work happens inside Ubuntu (`apt-install.sh`); this screen is a
 * native front end with a featured page and a search of the archive.
 */
@Composable
fun StoreScreen(
    /** Runs a command inside the container and returns its output. */
    runGuest: suspend (command: String, timeoutSeconds: Long) -> String,
    /** Starts an installed application on the desktop. */
    openApp: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var source by remember { mutableStateOf(StoreSource.UBUNTU) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // Package name (or Flathub app id) -> installed, for everything on screen.
    val installed = remember { mutableStateMapOf<String, Boolean>() }
    var installing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshStatus(apps: List<StoreApp>) {
        if (apps.isEmpty()) return
        val (flatpaks, debs) = apps.partition { it.flatpak }
        if (debs.isNotEmpty()) {
            val out = runGuest(
                "/usr/local/lib/linx/apt-install.sh --status " + debs.joinToString(" ") { it.pkg },
                60,
            )
            out.lines().forEach { line ->
                val parts = line.trim().split(" ")
                if (parts.size == 2) installed[parts[0]] = parts[1] == "installed"
            }
        }
        if (flatpaks.isNotEmpty()) {
            val ids = runGuest("/usr/local/lib/linx/flatpak-list.sh", 60).lines()
                .map { it.substringBefore('|').trim() }.toSet()
            flatpaks.forEach { installed[it.pkg] = it.pkg in ids }
        }
    }

    LaunchedEffect(source) {
        refreshStatus(if (source == StoreSource.FLATHUB) FlathubCatalog.featured else StoreCatalog.featured)
    }

    // Searching runs apt or Flatpak inside the container, which takes a
    // moment, so it waits for the typing to pause.
    LaunchedEffect(query, source) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        results = if (source == StoreSource.FLATHUB) {
            // The whole catalogue, filtered here: Flatpak's own search needs
            // an appstream download the container does not have.
            val words = query.trim().lowercase().split(Regex("\\s+"))
            runGuest("/usr/local/lib/linx/flatpak-search.sh flathub", 300).lines().mapNotNull { line ->
                val p = line.split("|").map { it.trim() }
                if (p.size < 3 || p[0].isBlank()) return@mapNotNull null
                val text = line.lowercase()
                if (words.any { it !in text }) null
                else {
                    val version = p[1].takeIf { it != "-" && it.isNotBlank() }?.let { " · $it" } ?: ""
                    flathubApp(p[0], p[2], p[0] + version, Icons.Default.Inventory2, 0xFF4A86CF)
                }
            }
                // Well-known projects first, then names that match best.
                .sortedBy { app ->
                    val id = app.pkg.lowercase()
                    val name = app.name.lowercase()
                    var score = 0
                    if (WELL_KNOWN.none { id.startsWith(it) }) score += 4
                    if (!name.startsWith(words.first())) score += 2
                    if (name.length > 24) score += 1
                    score
                }
                .take(40)
        } else {
            runGuest("/usr/local/lib/linx/apt-install.sh --search ${shellQuote(query.trim())}", 300).lines().mapNotNull { line ->
                val p = line.split("|", limit = 2)
                if (p.size < 2 || p[0].isBlank() || p[0].contains(' ')) null
                else StoreApp(pkg = p[0].trim(), name = p[0].trim(), summary = p[1].trim(), command = listOf(p[0].trim()))
            }
        }
        refreshStatus(results)
        searching = false
    }

    fun install(app: StoreApp) {
        scope.launch {
            installing = app.pkg
            message = if (app.flatpak) {
                "Installing ${app.name} from Flathub… the first app also brings its runtime, a few hundred MB."
            } else {
                "Installing ${app.name}… the first install also downloads Ubuntu's package lists."
            }
            val cmd = app.installer ?: ("/usr/local/lib/linx/apt-install.sh " + app.packages.joinToString(" "))
            val out = runGuest("$cmd 2>&1 | tail -n 3", 1800)
            refreshStatus(listOf(app))
            message = if (installed[app.pkg] == true) {
                "${app.name} is installed. It is in the Applications menu on the desktop."
            } else {
                "${app.name} could not be installed: " +
                    (out.trim().lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "no output")
            }
            installing = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UbuntuPalette.LightBackground),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(UbuntuPalette.Aubergine)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Software", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (source == StoreSource.FLATHUB) "Linux apps from Flathub" else "Desktop apps from Ubuntu",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
            }
            StoreSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = if (source == StoreSource.FLATHUB) "Search Flathub" else "Search Ubuntu packages",
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Row(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(4.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                StoreSource.entries.forEach { s ->
                    val selected = s == source
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Color.White else Color.Transparent)
                            .clickable { source = s }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            s.label,
                            color = if (selected) UbuntuPalette.Aubergine else Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (searching || installing != null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = UbuntuPalette.UbuntuOrange,
                trackColor = UbuntuPalette.LightSurfaceVariant,
            )
        }

        message?.let { msg ->
            Row(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(UbuntuPalette.LightSurfaceVariant)
                    .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
                    .align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    msg,
                    fontSize = 13.sp,
                    color = UbuntuPalette.LightOnSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { message = null }) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = UbuntuPalette.LightOnSurfaceVariant)
                }
            }
        }

        val showing = when {
            query.isNotBlank() -> results
            source == StoreSource.FLATHUB -> FlathubCatalog.featured
            else -> StoreCatalog.featured
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    when {
                        query.isBlank() -> "Featured"
                        searching -> "Searching…"
                        results.isEmpty() -> "Nothing matches “$query”."
                        source == StoreSource.FLATHUB -> "Apps"
                        else -> "Packages"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = UbuntuPalette.LightOnSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(showing, key = { it.pkg }) { app ->
                StoreRow(
                    app = app,
                    installed = installed[app.pkg],
                    busy = installing == app.pkg,
                    enabled = installing == null,
                    onInstall = { install(app) },
                    onOpen = { openApp(app.command) },
                )
            }
        }
    }
}

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

@Composable
private fun StoreSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = UbuntuPalette.LightOnSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, color = UbuntuPalette.LightOnSurfaceVariant, fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = UbuntuPalette.LightOnSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(UbuntuPalette.UbuntuOrange),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = UbuntuPalette.LightOnSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StoreRow(
    app: StoreApp,
    installed: Boolean?,
    busy: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(UbuntuPalette.LightSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(app.accent)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(app.icon ?: Icons.Default.Inventory2, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = UbuntuPalette.LightOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.summary,
                fontSize = 12.sp,
                color = UbuntuPalette.LightOnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val canOpen = installed == true && app.command.isNotEmpty()
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        busy -> UbuntuPalette.LightSurfaceVariant
                        canOpen -> UbuntuPalette.Aubergine
                        installed == true -> UbuntuPalette.LightSurfaceVariant
                        enabled -> UbuntuPalette.UbuntuOrange
                        else -> UbuntuPalette.LightOutline
                    },
                )
                .clickable(enabled = !busy && (canOpen || (installed != true && enabled))) {
                    if (canOpen) onOpen() else onInstall()
                }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = UbuntuPalette.UbuntuOrange,
                )
                canOpen -> Text("Open", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                installed == true -> Text("Installed", color = UbuntuPalette.LightOnSurface, fontSize = 13.sp)
                else -> Text("Install", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A Flathub application (still used by the launcher's installed-app list). */
data class FlatpakApp(
    val id: String,
    val name: String,
    val version: String = "",
)
