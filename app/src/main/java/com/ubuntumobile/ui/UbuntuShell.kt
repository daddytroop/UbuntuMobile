package com.ubuntumobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette

/**
 * The Ubuntu desktop shell.
 *
 * Layout adapts to the available space:
 *  - portrait / compact : bottom dash, full-screen app grid
 *  - landscape / wide   : side dock, grid on the right
 */
@Composable
fun UbuntuShell(
    state: UbuntuState,
    apps: List<AppEntry>,
    wide: Boolean,
    onLaunchApp: (AppEntry) -> Unit,
    onOpenStore: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var overview by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.name.contains(query, ignoreCase = true) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        UbuntuPalette.Aubergine,
                        UbuntuPalette.AubergineDark,
                        Color(0xFF2A0B27),
                    )
                )
            )
    ) {
        // ---- Top bar ----
        TopBar(
            clock = rememberClock(),
            onActivities = { overview = !overview },
            activitiesActive = overview,
            wide = wide,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // ---- Main content ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp)
                // Keep the content clear of the dash: in landscape the dash
                // is a column on the left, in portrait a row at the bottom.
                .padding(start = if (wide) 96.dp else 0.dp)
                .padding(bottom = if (wide) 24.dp else 100.dp),
        ) {
            if (overview) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AppGrid(
                    apps = filtered,
                    onLaunch = onLaunchApp,
                    wide = wide,
                    modifier = Modifier.weight(1f),
                )
            } else {
                WelcomePanel(
                    state = state,
                    onOpenStore = onOpenStore,
                    onRefresh = onRefresh,
                    modifier = Modifier
                        // A phone in landscape is very wide; the panel
                        // should not stretch across the whole screen.
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.CenterHorizontally),
                )
            }
        }

        // ---- Dash (a column on the left in landscape, a row at the
        //      bottom in portrait) ----
        AnimatedVisibility(
            visible = !overview,
            enter = if (wide) {
                slideInHorizontally { -it } + fadeIn()
            } else {
                slideInVertically { it } + fadeIn()
            },
            exit = if (wide) {
                slideOutHorizontally { -it } + fadeOut()
            } else {
                slideOutVertically { it } + fadeOut()
            },
            modifier = if (wide) {
                Modifier.align(Alignment.BottomStart)
            } else {
                Modifier.align(Alignment.BottomCenter)
            },
        ) {
            Dash(
                apps = apps.filter { AppCatalog.dashFavorites.contains(it.id) },
                onLaunch = onLaunchApp,
                vertical = wide,
            )
        }
    }
}

/** GNOME-style top bar with the Activities button and a clock. */
@Composable
private fun TopBar(
    clock: String,
    onActivities: () -> Unit,
    activitiesActive: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Activities
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onActivities() }
                    .background(
                        if (activitiesActive) Color.White.copy(alpha = 0.22f) else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Activities",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Clock
            Text(
                text = clock,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            // Status icons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(UbuntuPalette.Success)
                StatusDot(UbuntuPalette.LightOnSurface)
                if (!wide) {
                    Text("100%", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** Overview search box. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Type to search…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedBorderColor = Color.White.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLeadingIconColor = Color.White,
            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.7f),
            cursorColor = Color.White,
            focusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { }),
    )
}

/** The app grid shown in the overview. */
@Composable
private fun AppGrid(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No applications found",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    val columns = if (wide) 5 else 3
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(apps, key = { it.id }) { app ->
            AppTile(app = app, onClick = { onLaunch(app) })
        }
    }
}

/** A single app icon in the grid. */
@Composable
fun AppTile(app: AppEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(app.accent),
                            Color(app.accent).copy(alpha = 0.75f),
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.icon,
                color = Color.White,
                fontSize = if (app.icon.length <= 2) 24.sp else 28.sp,
                fontFamily = if (app.icon.length <= 2) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The dash (Ubuntu dock) with favourite apps. */
@Composable
private fun Dash(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    vertical: Boolean,
) {
    // A row of icons has to fit the width of a phone, so the icons are
    // smaller when the dash is horizontal and the row can be scrolled if
    // there are more favourites than fit.
    val iconSize = if (vertical) 48.dp else 40.dp
    val spacing = if (vertical) 8.dp else 6.dp

    Surface(
        color = Color.Black.copy(alpha = 0.30f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(12.dp),
    ) {
        if (vertical) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                apps.forEach { DashIcon(it, iconSize) { onLaunch(it) } }
            }
        } else {
            Row(
                modifier = Modifier
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                apps.forEach { DashIcon(it, iconSize) { onLaunch(it) } }
            }
        }
    }
}

@Composable
private fun DashIcon(app: AppEntry, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(app.accent))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = app.icon,
            color = Color.White,
            fontSize = 20.sp,
            fontFamily = if (app.icon.length <= 2) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The home screen shown when the overview is closed. */
@Composable
private fun WelcomePanel(
    state: UbuntuState,
    onOpenStore: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Ubuntu 26.04 LTS",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "A complete Linux system running on your phone.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            InfoRow("Kernel", state.guestInfo.kernel)
            InfoRow("Architecture", state.guestInfo.architecture)
            InfoRow("Flatpak", state.guestInfo.flatpakVersion)
            InfoRow("Flathub", state.guestInfo.flathubRemotes.joinToString(", ").ifEmpty { "not configured" })
            InfoRow("Disk", state.guestInfo.diskUsage)

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Ubuntu Software", onOpenStore)
                PillButton("Refresh", onRefresh)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.sp,
        )
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            // The value takes what is left of the row, so a long kernel
            // string is shortened instead of pushing the label around.
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
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
        while (true) {
            val cal = java.util.Calendar.getInstance()
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val m = cal.get(java.util.Calendar.MINUTE)
            text = String.format("%02d:%02d", h, m)
            kotlinx.coroutines.delay(10_000)
        }
    }
    return text
}
