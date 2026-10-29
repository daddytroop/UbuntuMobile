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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
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

    LaunchedEffect(Unit) {
        loading = true
        results = try {
            searchRemote("")
        } catch (e: Exception) {
            message = e.message ?: "Could not reach Flathub"
            emptyList()
        }
        loading = false
    }

    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        loading = true
        results = try {
            searchRemote(query)
        } catch (e: Exception) {
            message = e.message ?: "Search failed"
            emptyList()
        }
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UbuntuPalette.LightBackground),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UbuntuPalette.Aubergine)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ubuntu Software",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PillButton("Close", onClose)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Search Flathub…") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = UbuntuPalette.LightSurface,
                unfocusedContainerColor = UbuntuPalette.LightSurface,
            ),
        )

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
        }

        message?.let { msg ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(UbuntuPalette.LightSurfaceVariant)
                    .padding(12.dp)
            ) {
                Text(msg, fontSize = 13.sp, color = UbuntuPalette.LightOnSurface)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { it.id }) { app ->
                StoreRow(
                    app = app,
                    busy = installing == app.id,
                    onInstall = {
                        scope.launch {
                            installing = app.id
                            message = "Installing ${app.name}…"
                            val out = installApp(app.id)
                            message = out.trim().takeLast(220).ifEmpty { "Done" }
                            installing = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StoreRow(app: FlatpakApp, busy: Boolean, onInstall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UbuntuPalette.LightSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(UbuntuPalette.Aubergine),
            contentAlignment = Alignment.Center,
        ) {
            Text("📦", fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = UbuntuPalette.LightOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.id,
                fontSize = 11.sp,
                color = UbuntuPalette.LightOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (busy) UbuntuPalette.LightSurfaceVariant else UbuntuPalette.UbuntuOrange)
                .clickable(enabled = !busy) { onInstall() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
