package com.ubuntumobile.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlin.math.roundToInt

/**
 * The small button that opens the app's menu, floating over the desktop at
 * the right edge. It can be dragged up and down, out of the way of whatever
 * is underneath.
 */
@Composable
fun MenuHandle(connected: Boolean, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val size = 44.dp
        val maxY = with(density) { (maxHeight - size).toPx() }.coerceAtLeast(0f)
        // Starts a third of the way down: clear of the desktop icons at the
        // top left and the panel at the bottom.
        var y by remember { mutableFloatStateOf(-1f) }
        if (y < 0f) y = maxY / 3f
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y.coerceIn(0f, maxY).roundToInt()) }
                .padding(end = 6.dp)
                .size(size)
                .clip(CircleShape)
                .background(UbuntuPalette.AubergineDeep.copy(alpha = 0.85f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .pointerInput(maxY) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        y = (y + drag.y).coerceIn(0f, maxY)
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // The app's mark: an orange dot, ringed while connecting.
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (connected) UbuntuPalette.UbuntuOrange else UbuntuPalette.Warning),
            )
        }
    }
}

/**
 * The app's menu: everything outside the Linux desktop itself, in a panel
 * that slides in from the right.
 */
@Composable
fun DesktopMenu(
    open: Boolean,
    connected: Boolean,
    actions: DisplayActions,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                lerp(UbuntuPalette.UbuntuOrange, Color(0xFF101014), 0.72f),
                                Color(0xFF16141A),
                                lerp(Color(0xFF77216F), Color(0xFF101014), 0.7f),
                            ),
                        ),
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.10f),
                        RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 20.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(connected)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Mobuntu", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (connected) "Desktop running" else "Starting…",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                fun go(action: () -> Unit): () -> Unit = {
                    onDismiss()
                    action()
                }

                MenuSection("Apps")
                MenuItem(Icons.Default.Apps, "All apps", go(actions.onShowLauncher))
                MenuItem(Icons.Default.Public, "Firefox", go(actions.onOpenBrowser), note = actions.browserNote)
                MenuItem(Icons.Default.Terminal, "Terminal", go(actions.onOpenTerminal))
                MenuItem(Icons.Default.Folder, "Files", go(actions.onOpenFiles))
                MenuItem(Icons.Default.ShoppingBag, "Software", go(actions.onOpenStore))

                MenuSection("Phone")
                MenuItem(Icons.Default.Tune, "Quick settings", go(actions.onQuickSettings))
                MenuItem(Icons.Default.Screenshot, "Screenshot", go(actions.onScreenshot))
                MenuItem(Icons.Default.Lock, "Lock", go(actions.onLock))

                MenuSection("Controls")
                MenuItem(Icons.Default.Keyboard, "Keyboard", go(actions.onShowKeyboard))
                MenuItem(Icons.Default.Gamepad, "Gamepad", go(actions.onOpenGamepad))
                actions.onUseMonitor?.let { MenuItem(Icons.Default.Cast, "Use the monitor", go(it)) }
                MenuItem(Icons.Default.Refresh, "Reconnect display", go(actions.onReconnect))

                MenuSection("App")
                MenuItem(Icons.Default.Settings, "Settings", go(actions.onOpenSettings))
                MenuItem(Icons.Default.PowerSettingsNew, "Power", go(actions.onPower))
            }
        }
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(
        title.uppercase(),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit, note: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = UbuntuPalette.UbuntuOrangeLight, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, color = Color.White, fontSize = 15.sp)
            if (note != null) Text(note, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}
