package com.ubuntumobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The application launcher, shown over the Linux desktop.
 *
 * A search field, the apps in a grid and a small card with what the
 * container is running on. It is the Android side of the system, so it
 * follows Android conventions: a back arrow returns to the desktop.
 */
@Composable
fun UbuntuShell(
    state: UbuntuState,
    apps: List<AppEntry>,
    /** The container's root, where the apps' icon files are. */
    iconRoot: java.io.File? = null,
    /** Put a shortcut to a Linux app on the phone's home screen. */
    onPin: ((AppEntry) -> Unit)? = null,
    wide: Boolean,
    onLaunchApp: (AppEntry) -> Unit,
    onOpenStore: () -> Unit,
    onRefresh: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.name.contains(query, ignoreCase = true) || it.summary.contains(query, ignoreCase = true)
        }
    }
    val linuxApps = filtered.filter {
        it.category == AppEntry.Category.LINUX || it.category == AppEntry.Category.TERMINAL
    }
    val flatpaks = filtered.filter { it.category == AppEntry.Category.FLATPAK }
    val system = filtered.filter { it.category == AppEntry.Category.SYSTEM }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(UbuntuPalette.Aubergine, UbuntuPalette.AubergineDark, UbuntuPalette.AubergineDeep),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the desktop", tint = Color.White)
            }
            Text(
                "Applications",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                rememberClock(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        SearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .align(Alignment.CenterHorizontally),
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (wide) 112.dp else 96.dp),
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 900.dp)
                .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(linuxApps, key = { it.id }) { app ->
                AppTile(app = app, iconRoot = iconRoot, onClick = { onLaunchApp(app) }, onPin = onPin.takeIf { app.isLinuxApp })
            }
            if (flatpaks.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("From Flathub") }
                items(flatpaks, key = { it.id }) { app ->
                    AppTile(app = app, iconRoot = iconRoot, onClick = { onLaunchApp(app) }, onPin = onPin.takeIf { app.isLinuxApp })
                }
            }
            if (system.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("System") }
                items(system, key = { it.id }) { app ->
                    AppTile(app = app, iconRoot = iconRoot, onClick = { onLaunchApp(app) }, onPin = onPin.takeIf { app.isLinuxApp })
                }
            }
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Nothing matches “$query”", color = Color.White.copy(alpha = 0.8f))
                        Spacer(Modifier.height(12.dp))
                        PillButton("Search Flathub", onOpenStore)
                    }
                }
            }
            if (query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SystemCard(
                        state = state,
                        onRefresh = onRefresh,
                        modifier = Modifier.padding(top = 20.dp, start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Overview search box. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search apps", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White)
            }
        }
    }
}

/** The icon the launcher draws for an app. */
private fun iconFor(app: AppEntry): ImageVector = when (app.id) {
    "terminal" -> Icons.Default.Terminal
    "browser" -> Icons.Default.Public
    "files" -> Icons.Default.Folder
    "text-editor" -> Icons.Default.EditNote
    "system-info" -> Icons.Default.Speed
    "settings" -> Icons.Default.Settings
    "store" -> Icons.Default.ShoppingBag
    "gamepad" -> Icons.Default.SportsEsports
    "console" -> Icons.Default.Code
    else -> Icons.Default.Widgets
}

/** Decoded icons, by path: the grid is rebuilt often, the files do not change. */
private val iconCache = android.util.LruCache<String, ImageBitmap>(96)

/**
 * An app's icon file from the container, as a bitmap; null while it loads
 * or when there is none. PNGs decode directly; SVGs (many themes' only
 * size for some apps) are rendered at 192 pixels.
 */
@Composable
private fun rememberAppIcon(root: java.io.File?, path: String?): ImageBitmap? {
    val icon by produceState(path?.let { iconCache.get(it) }, root, path) {
        if (value != null || root == null || path == null) return@produceState
        value = withContext(Dispatchers.IO) {
            decodeAppIcon(root, path)?.asImageBitmap()?.also { iconCache.put(path, it) }
        }
    }
    return icon
}

/**
 * An app's icon file from the container (a container path under [root]) as
 * a bitmap, or null. SVGs are rendered at [size] pixels. Slow: off the main
 * thread. Also used for notifications and home-screen shortcuts.
 */
fun decodeAppIcon(root: java.io.File, path: String, size: Int = 192): android.graphics.Bitmap? = try {
    val file = java.io.File(root, path.removePrefix("/"))
    if (path.endsWith(".svg")) {
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(file.inputStream())
        // Without a viewBox a resized SVG is drawn at its own size in a corner.
        if (svg.documentViewBox == null && svg.documentWidth > 0f && svg.documentHeight > 0f) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        svg.documentWidth = size.toFloat()
        svg.documentHeight = size.toFloat()
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(android.graphics.Canvas(bmp))
        bmp
    } else {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/** A single app in the grid. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun AppTile(
    app: AppEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRoot: java.io.File? = null,
    /** A long press offers "Add to home screen". */
    onPin: ((AppEntry) -> Unit)? = null,
) {
    val picture = rememberAppIcon(iconRoot, app.iconPath)
    var menu by remember { mutableStateOf(false) }
    // The menu opens at the tile: it is anchored to the Box around it.
    Box(modifier) {
    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Add to home screen") },
            onClick = {
                menu = false
                onPin?.invoke(app)
            },
        )
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (onPin != null) ({ menu = true }) else null,
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (picture != null) {
            // The app's own icon, as the desktop's menu shows it.
            Image(picture, contentDescription = null, modifier = Modifier.size(60.dp).padding(2.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(app.accent), Color(app.accent).copy(alpha = 0.78f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(app), contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = app.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
    }
}

/** What the container is running on, read from the guest. */
@Composable
private fun SystemCard(state: UbuntuState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val info = state.guestInfo
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UbuntuLogo(28.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.prettyName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Running under proot on Android",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White.copy(alpha = 0.8f))
            }
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("Architecture", info.architecture)
        InfoRow("Kernel", info.kernel.substringBefore('-'))
        InfoRow("Flatpak", info.flatpakVersion.removePrefix("Flatpak "))
        InfoRow("Flathub", if (info.flathubRemotes.isEmpty()) "not configured" else "configured")
        InfoRow("Storage", diskSummary(info.diskUsage))
    }
}

/** "/dev/block/dm-40 7.8G 2.5G 5.2G 33% /" becomes "5.2G free of 7.8G". */
private fun diskSummary(df: String): String {
    val fields = df.trim().split(Regex("\\s+"))
    return if (fields.size >= 4) "${fields[3]} free of ${fields[1]}" else df.ifBlank { "-" }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
        )
    }
}

/** The Ubuntu "circle of friends", drawn rather than shipped as an image. */
@Composable
fun UbuntuLogo(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(UbuntuPalette.UbuntuOrange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.42f)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Simple clock shown in the top bar. */
@Composable
fun rememberClock(): String {
    var text by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        while (true) {
            text = format.format(java.util.Date())
            kotlinx.coroutines.delay(10_000)
        }
    }
    return text
}
