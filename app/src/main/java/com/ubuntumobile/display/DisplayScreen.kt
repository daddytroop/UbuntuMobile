package com.ubuntumobile.display

import android.view.KeyEvent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Linux display: an X11 desktop running inside the Ubuntu container,
 * streamed to the phone over the RFB protocol.
 *
 * Touch input is translated into pointer events and the on-screen keyboard
 * supplies key events, so ordinary X applications can be driven.
 */
@Composable
fun DisplayScreen(
    session: DisplaySession,
    landscape: Boolean,
    onClose: () -> Unit,
    onShowLauncher: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenStore: () -> Unit = {},
    onOpenGamepad: () -> Unit = {},
    onShowKeyboard: () -> Unit = {},
    /** Reports the size, in pixels, of the area the desktop is drawn in. */
    onCanvasSize: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var frameTick by remember { mutableIntStateOf(0) }
    var connecting by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var showHint by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // The X server needs a moment before it accepts connections, and on a
    // phone that can take a while - the desktop also has to start. Keep
    // trying instead of giving up after the first refused connection.
    //
    // The connection itself is network I/O, which Android refuses to do on
    // the main thread (NetworkOnMainThreadException).
    suspend fun connect() {
        connecting = true
        attempt = 0
        var tries = 0
        while (!session.connected && tries < 120) {
            if (withContext(Dispatchers.IO) { session.start() }) break
            tries++
            attempt = tries
            delay(1000)
        }
        connecting = !session.connected
        if (session.connected) session.refresh()
    }

    LaunchedEffect(Unit) {
        session.onFrame = { frameTick++ }
        connect()
        // If the stream drops later (the server restarted, the phone was
        // asleep), connect again on our own.
        while (true) {
            delay(2000)
            if (!connecting && !session.connected && attempt < 120) {
                withContext(Dispatchers.IO) { session.stop() }
                connect()
            }
        }
    }

    LaunchedEffect(session.connected) {
        if (session.connected) {
            delay(6000)
            showHint = false
        }
    }

    val bmp = session.bitmap

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UbuntuPalette.DarkBackground),
    ) {
        DisplayToolbar(
            connected = session.connected,
            onShowLauncher = onShowLauncher,
            onOpenTerminal = onOpenTerminal,
            onOpenFiles = onOpenFiles,
            onOpenStore = onOpenStore,
            onOpenGamepad = onOpenGamepad,
            onShowKeyboard = onShowKeyboard,
            onReconnect = {
                scope.launch {
                    withContext(Dispatchers.IO) { session.stop() }
                    connect()
                }
            },
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val canvasW = constraints.maxWidth
            val canvasH = constraints.maxHeight
            LaunchedEffect(canvasW, canvasH) { onCanvasSize(IntSize(canvasW, canvasH)) }

            if (connecting || !session.connected || bmp == null) {
                ConnectingPanel(
                    connecting = connecting,
                    attempt = attempt,
                    error = session.error,
                    onRetry = { scope.launch { connect() } },
                )
            } else {
                // Fit the framebuffer into the space that is actually
                // available, centred, and keep the mapping so touches land
                // on the right framebuffer pixel.
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val dstW = (bmp.width * scale).toInt()
                val dstH = (bmp.height * scale).toInt()
                val origin = Offset((boxW - dstW) / 2f, (boxH - dstH) / 2f)

                fun fbX(p: Offset) = ((p.x - origin.x) / scale).toInt().coerceIn(0, bmp.width - 1)
                fun fbY(p: Offset) = ((p.y - origin.y) / scale).toInt().coerceIn(0, bmp.height - 1)

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bmp.width, bmp.height, scale) {
                            detectTapGestures(
                                onTap = { p ->
                                    // A tap is a left click.
                                    session.pointer(fbX(p), fbY(p), 0)
                                    session.pointer(fbX(p), fbY(p), 1)
                                    session.pointer(fbX(p), fbY(p), 0)
                                },
                                onDoubleTap = { p ->
                                    val x = fbX(p)
                                    val y = fbY(p)
                                    repeat(2) {
                                        session.pointer(x, y, 1)
                                        session.pointer(x, y, 0)
                                    }
                                },
                                onLongPress = { p ->
                                    // Long press sends a right click.
                                    session.pointer(fbX(p), fbY(p), 4)
                                    session.pointer(fbX(p), fbY(p), 0)
                                },
                            )
                        }
                        .pointerInput(bmp.width, bmp.height, scale) {
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { p ->
                                    last = p
                                    session.pointer(fbX(p), fbY(p), 0)
                                    session.pointer(fbX(p), fbY(p), 1)
                                },
                                onDrag = { change, _ ->
                                    last = change.position
                                    session.pointer(fbX(last), fbY(last), 1)
                                },
                                // Release where the finger was lifted, not
                                // at the corner of the screen.
                                onDragEnd = { session.pointer(fbX(last), fbY(last), 0) },
                                onDragCancel = { session.pointer(fbX(last), fbY(last), 0) },
                            )
                        }
                        .pointerInput(bmp.width, bmp.height, scale) {
                            // Two fingers scrolling is the only way to
                            // scroll a window on a touch screen.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var travelled = 0f
                                var last = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val centre = (pressed[0].position + pressed[1].position) / 2f
                                        if (last != 0f) {
                                            travelled += last - centre.y
                                            val steps = (travelled / 24f).toInt()
                                            if (steps != 0) {
                                                travelled -= steps * 24f
                                                session.scroll(fbX(centre), fbY(centre), steps)
                                            }
                                        }
                                        last = centre.y
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        last = 0f
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    // `frameTick` forces a redraw when new pixels arrive.
                    @Suppress("UNUSED_EXPRESSION")
                    frameTick
                    synchronized(bmp) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()),
                            dstSize = IntSize(dstW, dstH),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showHint,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp),
                ) {
                    Text(
                        "Tap to click · Hold to right-click · Two fingers to scroll",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .clickable { showHint = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The strip above the Linux desktop.
 *
 * A phone is too narrow for every action, so the ones used all the time
 * stay visible and the rest live in an overflow menu.
 */
@Composable
private fun DisplayToolbar(
    connected: Boolean,
    onShowLauncher: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenGamepad: () -> Unit,
    onShowKeyboard: () -> Unit,
    onReconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(UbuntuPalette.DarkSurface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShowLauncher) {
            Icon(Icons.Default.Apps, contentDescription = "Apps", tint = Color.White)
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) UbuntuPalette.Success else UbuntuPalette.Warning),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Ubuntu",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenTerminal) {
            Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = Color.White)
        }
        IconButton(onClick = onShowKeyboard) {
            Icon(Icons.Default.Keyboard, contentDescription = "Keyboard", tint = Color.White)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuEntry("Files", Icons.Default.Folder) { menu = false; onOpenFiles() }
                MenuEntry("Ubuntu Software", Icons.Default.ShoppingBag) { menu = false; onOpenStore() }
                MenuEntry("Gamepad", Icons.Default.Gamepad) { menu = false; onOpenGamepad() }
                MenuEntry("Reconnect display", Icons.Default.Refresh) { menu = false; onReconnect() }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Shown while the X server and the desktop are still starting. */
@Composable
private fun ConnectingPanel(
    connecting: Boolean,
    attempt: Int,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(UbuntuPalette.AubergineDark, Color(0xFF2A0B27), Color(0xFF140512)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (connecting) {
                    CircularProgressIndicator(
                        color = UbuntuPalette.UbuntuOrange,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(72.dp),
                    )
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(UbuntuPalette.UbuntuOrange),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (connecting) "Starting the desktop" else "The desktop is not responding",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    connecting && attempt < 15 -> "Starting the X server and the panel…"
                    connecting -> "This is taking longer than usual…"
                    else -> error ?: "The display did not come up."
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (!connecting) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Try again",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(UbuntuPalette.UbuntuOrange)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Map an Android key event to an X11 keysym.
 *
 * Covers the keys that matter for shells and editors.
 */
fun androidKeyToX11(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER -> 0xFF0D
    KeyEvent.KEYCODE_DEL -> 0xFF08
    KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
    KeyEvent.KEYCODE_TAB -> 0xFF09
    KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
    KeyEvent.KEYCODE_SPACE -> 0x0020
    KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
    KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
    KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
    KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE3
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE1
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFE9
    KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
    KeyEvent.KEYCODE_MOVE_END -> 0xFF57
    KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
    KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
    else -> keyEventToUnicode(keyCode)?.code
}

private fun keyEventToUnicode(keyCode: Int): Char? {
    if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
        return '0' + (keyCode - KeyEvent.KEYCODE_0)
    }
    if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        // X11 keysyms for Latin letters are their lower-case code points;
        // the server applies Shift itself.
        return 'a' + (keyCode - KeyEvent.KEYCODE_A)
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_MINUS -> '-'
        KeyEvent.KEYCODE_EQUALS -> '='
        KeyEvent.KEYCODE_PERIOD -> '.'
        KeyEvent.KEYCODE_COMMA -> ','
        KeyEvent.KEYCODE_SLASH -> '/'
        KeyEvent.KEYCODE_BACKSLASH -> '\\'
        KeyEvent.KEYCODE_SEMICOLON -> ';'
        KeyEvent.KEYCODE_APOSTROPHE -> '\''
        KeyEvent.KEYCODE_GRAVE -> '`'
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'
        else -> null
    }
}
