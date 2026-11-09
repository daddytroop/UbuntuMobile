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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Cast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the display's toolbar can do. */
class DisplayActions(
    val onShowLauncher: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onOpenFiles: () -> Unit = {},
    val onOpenStore: () -> Unit = {},
    val onOpenGamepad: () -> Unit = {},
    val onShowKeyboard: () -> Unit = {},
    val onReconnect: () -> Unit = {},
    /** Move the desktop to the external monitor; null when there is none. */
    val onUseMonitor: (() -> Unit)? = null,
    val onOpenBrowser: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** What is happening with the browser, e.g. "Installing Firefox…"; null when it is ready. */
    val browserNote: String? = null,
    /** The apps chosen at setup installing; null when there is nothing to show. */
    val setup: SetupStatus? = null,
    val onRetrySetup: () -> Unit = {},
)

/**
 * The Linux display: an X11 desktop running inside the Ubuntu container,
 * streamed to the phone over the RFB protocol.
 *
 * The desktop takes the whole screen. Everything that belongs to the app
 * rather than to Linux - the app grid, the store, the keyboard, settings -
 * sits behind one small floating button, which opens a side menu.
 */
@Composable
fun DisplayScreen(
    session: DisplaySession,
    actions: DisplayActions,
    /** Reports the size, in pixels, of the area the desktop is drawn in. */
    onCanvasSize: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Drawn over the desktop, e.g. the gamepad; touches it leaves alone reach the desktop. */
    overlay: @Composable () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        DesktopSurface(
            session = session,
            onCanvasSize = onCanvasSize,
            modifier = Modifier.fillMaxSize(),
            showHint = true,
        )
        overlay()
        SetupBanner(actions.setup, actions.onRetrySetup, Modifier.align(Alignment.TopCenter))
        MenuHandle(connected = session.connected, onClick = { menuOpen = true })
        DesktopMenu(
            open = menuOpen,
            connected = session.connected,
            actions = actions,
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * The desktop itself, scaled to fit, with touch and mouse input; or the
 * "starting" panel while there is no picture yet. The phone's screen and an
 * external monitor both show it.
 */
@Composable
fun DesktopSurface(
    session: DisplaySession,
    onCanvasSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = false,
) {
    // The gesture hint is shown once per run, not every time the desktop
    // comes back from another screen.
    var hintVisible by remember { mutableStateOf(showHint && !hintShown) }
    LaunchedEffect(session.connected) {
        if (session.connected && hintVisible) {
            hintShown = true
            delay(6000)
            hintVisible = false
        }
    }

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val canvasW = constraints.maxWidth
        val canvasH = constraints.maxHeight
        LaunchedEffect(canvasW, canvasH) { onCanvasSize(IntSize(canvasW, canvasH)) }

        val bmp = session.bitmap
        if (session.connecting || !session.connected || bmp == null) {
            ConnectingPanel(
                connecting = session.connecting,
                attempt = session.attempt,
                error = session.error,
                onRetry = { session.retry() },
            )
            return@BoxWithConstraints
        }

        // Fit the framebuffer into the space that is actually available,
        // centred, and keep the mapping so input lands on the right pixel.
        // After a resize the two shapes match and the picture fills it.
        val boxW = canvasW.toFloat()
        val boxH = canvasH.toFloat()
        val scale = minOf(boxW / bmp.width, boxH / bmp.height)
        val dstW = (bmp.width * scale).toInt()
        val dstH = (bmp.height * scale).toInt()
        val origin = Offset((boxW - dstW) / 2f, (boxH - dstH) / 2f)

        fun fbX(p: Offset) = ((p.x - origin.x) / scale).toInt().coerceIn(0, bmp.width - 1)
        fun fbY(p: Offset) = ((p.y - origin.y) / scale).toInt().coerceIn(0, bmp.height - 1)

        // Set while a mouse is in use, so the touch gestures leave its
        // events alone.
        var mouse by remember { mutableStateOf(false) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bmp.width, bmp.height, scale) {
                    // A mouse (or a touchpad acting as one) is a real
                    // pointer: it moves without a button, has several
                    // buttons and a wheel, so it maps straight onto X.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            mouse = change.type == PointerType.Mouse
                            if (!mouse) continue
                            val x = fbX(change.position)
                            val y = fbY(change.position)
                            if (event.type == PointerEventType.Scroll) {
                                val dy = change.scrollDelta.y
                                val steps = maxOf(1, abs(dy).roundToInt())
                                // Down the screen is negative for X's wheel.
                                if (dy != 0f) session.scroll(x, y, if (dy > 0) -steps else steps)
                            } else {
                                session.pointer(x, y, buttonMask(event.buttons))
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
                .pointerInput(bmp.width, bmp.height, scale) {
                    detectTapGestures(
                        onTap = { p ->
                            if (mouse) return@detectTapGestures
                            // A tap is a left click.
                            session.pointer(fbX(p), fbY(p), 0)
                            session.pointer(fbX(p), fbY(p), 1)
                            session.pointer(fbX(p), fbY(p), 0)
                        },
                        onDoubleTap = { p ->
                            if (mouse) return@detectTapGestures
                            val x = fbX(p)
                            val y = fbY(p)
                            repeat(2) {
                                session.pointer(x, y, 1)
                                session.pointer(x, y, 0)
                            }
                        },
                        onLongPress = { p ->
                            if (mouse) return@detectTapGestures
                            // Long press sends a right click.
                            session.pointer(fbX(p), fbY(p), 4)
                            session.pointer(fbX(p), fbY(p), 0)
                        },
                    )
                }
                .pointerInput(bmp.width, bmp.height, scale) {
                    var last = Offset.Zero
                    var dragging = false
                    detectDragGestures(
                        onDragStart = { p ->
                            dragging = !mouse
                            if (!dragging) return@detectDragGestures
                            last = p
                            session.pointer(fbX(p), fbY(p), 0)
                            session.pointer(fbX(p), fbY(p), 1)
                        },
                        onDrag = { change, _ ->
                            if (!dragging) return@detectDragGestures
                            last = change.position
                            session.pointer(fbX(last), fbY(last), 1)
                        },
                        // Release where the finger was lifted, not at the
                        // corner of the screen.
                        onDragEnd = { if (dragging) session.pointer(fbX(last), fbY(last), 0) },
                        onDragCancel = { if (dragging) session.pointer(fbX(last), fbY(last), 0) },
                    )
                }
                .pointerInput(bmp.width, bmp.height, scale) {
                    // Two fingers scrolling is the only way to scroll a
                    // window on a touch screen.
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
                                    // Fingers moving up push the content up
                                    // (the wheel turning down), as on a phone.
                                    travelled += centre.y - last
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
            // Reading `frames` redraws the canvas when new pixels arrive.
            @Suppress("UNUSED_EXPRESSION")
            session.frames
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
            visible = hintVisible,
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
                    .clickable { hintVisible = false }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Whether the gesture hint has been shown in this run of the app. */
private var hintShown = false

/** The RFB button mask for the mouse buttons that are down. */
fun buttonMask(buttons: PointerButtons): Int =
    (if (buttons.isPrimaryPressed) 1 else 0) or
        (if (buttons.isTertiaryPressed) 2 else 0) or
        (if (buttons.isSecondaryPressed) 4 else 0)

@Composable
fun StatusDot(connected: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (connected) UbuntuPalette.Success else UbuntuPalette.Warning),
    )
}

@Composable
fun MenuEntry(label: String, icon: ImageVector, onClick: () -> Unit) {
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
 * Covers the keys of a full PC keyboard, so a USB or Bluetooth keyboard
 * works in Linux applications as it would on a desktop.
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
    KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
    KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
    KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
    KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
    KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
    KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
    // The Windows key: XFCE opens its menu with Super.
    KeyEvent.KEYCODE_META_LEFT -> 0xFFEB
    KeyEvent.KEYCODE_META_RIGHT -> 0xFFEC
    KeyEvent.KEYCODE_CAPS_LOCK -> 0xFFE5
    KeyEvent.KEYCODE_NUM_LOCK -> 0xFF7F
    KeyEvent.KEYCODE_SCROLL_LOCK -> 0xFF14
    KeyEvent.KEYCODE_MENU -> 0xFF67
    KeyEvent.KEYCODE_INSERT -> 0xFF63
    KeyEvent.KEYCODE_SYSRQ -> 0xFF61
    KeyEvent.KEYCODE_BREAK -> 0xFF13
    KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
    KeyEvent.KEYCODE_MOVE_END -> 0xFF57
    KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
    KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xFFBE + (keyCode - KeyEvent.KEYCODE_F1)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 0xFFB0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF8D
    KeyEvent.KEYCODE_NUMPAD_ADD -> 0xFFAB
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0xFFAD
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0xFFAA
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0xFFAF
    KeyEvent.KEYCODE_NUMPAD_DOT -> 0xFFAE
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
        KeyEvent.KEYCODE_AT -> '@'
        KeyEvent.KEYCODE_POUND -> '#'
        KeyEvent.KEYCODE_STAR -> '*'
        KeyEvent.KEYCODE_PLUS -> '+'
        else -> null
    }
}
