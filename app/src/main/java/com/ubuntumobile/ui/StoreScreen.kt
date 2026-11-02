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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Ubuntu Software" — browse and install applications from Flathub.
 *
 * All the work happens inside the Ubuntu container; this screen is a thin
 * native UI over `flatpak remote-ls` / `flatpak install`.
 */
@Composable
fun StoreScreen(
    searchRemote: suspend (String) -> List<FlatpakApp>,
    installApp: suspend (String) -> String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FlatpakApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Every search runs flatpak inside the container, which takes a while,
    // so wait for the typing to pause instead of searching per keystroke.
    // An empty query lists the catalogue.
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(450)
        loading = true
        results = try {
            searchRemote(query.trim())
        } catch (e: Exception) {
            message = e.message ?: "Could not reach Flathub"
            emptyList()
        }
        loading = false
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
                    Text("Ubuntu Software", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text("Apps from Flathub", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
            StoreSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .align(Alignment.CenterHorizontally),
            )
        }

        if (loading) {
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

        if (!loading && results.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (query.isBlank()) "Flathub could not be reached." else "No apps match “$query”.",
                    color = UbuntuPalette.LightOnSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { app ->
                StoreRow(
                    app = app,
                    busy = installing == app.id,
                    enabled = installing == null,
                    onInstall = {
                        scope.launch {
                            installing = app.id
                            message = "Installing ${app.name}… this can take several minutes."
                            val out = installApp(app.id)
                            message = out.trim().lines().lastOrNull { it.isNotBlank() }
                                ?.take(220) ?: "${app.name} is installed."
                            installing = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StoreSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
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
                Text("Search Flathub", color = UbuntuPalette.LightOnSurfaceVariant, fontSize = 16.sp)
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

/** A stable colour per app, so the letter tiles are told apart at a glance. */
private val AVATAR_COLOURS = listOf(
    0xFFE95420, 0xFF77216F, 0xFF3584E4, 0xFF26A269, 0xFFC64600, 0xFF9141AC, 0xFF1C71D8, 0xFF5E5C64,
)

@Composable
private fun StoreRow(app: FlatpakApp, busy: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    val colour = Color(AVATAR_COLOURS[Math.floorMod(app.id.hashCode(), AVATAR_COLOURS.size)])
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
                .background(colour),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
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
                listOf(app.version, app.id).filter { it.isNotBlank() && it != "-" }.joinToString(" · "),
                fontSize = 12.sp,
                color = UbuntuPalette.LightOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    when {
                        busy -> UbuntuPalette.LightSurfaceVariant
                        enabled -> UbuntuPalette.UbuntuOrange
                        else -> UbuntuPalette.LightOutline
                    },
                )
                .clickable(enabled = enabled && !busy) { onInstall() }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = UbuntuPalette.UbuntuOrange,
                )
            } else {
                Text("Install", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A Flathub application. */
data class FlatpakApp(
    val id: String,
    val name: String,
    val version: String = "",
)
