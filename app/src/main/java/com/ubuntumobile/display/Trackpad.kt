package com.ubuntumobile.display

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The phone's screen while the desktop is on a monitor: a trackpad, the
 * mouse buttons and the keyboard.
 *
 * A physical mouse plugged into the dock (or paired over Bluetooth) moves
 * the phone's own pointer, which is on the phone's screen, not the monitor.
 * So the trackpad captures it: once captured, its movements arrive here as
 * relative motion and drive the X pointer on the monitor directly.
 */
@Composable
fun TrackpadScreen(
    session: DisplaySession,
    actions: DisplayActions,
    /** Bring the desktop back to the phone's screen. */
    onShowOnPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var captured by remember { mutableStateOf(false) }
    // The buttons held on the on-screen bar, so a drag can use them.
    var heldButtons by remember { mutableStateOf(0) }

    DisposableEffect(view) {
        view.setOnCapturedPointerListener { _, event ->
            handleCapturedMouse(session, event)
            true
        }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            // Android drops the capture when the window loses focus.
            if (!focused) captured = false
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose {
            view.setOnCapturedPointerListener(null)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            if (view.hasPointerCapture()) view.releasePointerCapture()
        }
    }

    fun capture() {
        view.requestPointerCapture()
        captured = true
    }

    fun release() {
        view.releasePointerCapture()
        captured = false
    }

    Column(modifier.fillMaxSize().background(UbuntuPalette.DarkBackground)) {
        TrackpadBar(session.connected, actions, onShowOnPhone)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF242424))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .pointerInput(Unit) { trackpadGestures(session) { heldButtons } }
                .pointerInput(Unit) {
                    // A mouse used on the phone's screen: capture it so it
                    // drives the pointer on the monitor instead.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.type == PointerType.Mouse } && !captured) capture()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(
                    if (session.connected) "Trackpad" else "Waiting for the desktop…",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Slide to move · Tap to click · Two-finger tap to right-click\n" +
                        "Two fingers to scroll · Hold, then slide to drag",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
            MouseChip(
                captured = captured,
                onClick = { if (captured) release() else capture() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MouseButton("Left", Modifier.weight(1f)) { down ->
                heldButtons = if (down) heldButtons or 1 else heldButtons and 1.inv()
                session.movePointer(0, 0, heldButtons)
            }
            MouseButton("Right", Modifier.weight(1f)) { down ->
                heldButtons = if (down) heldButtons or 4 else heldButtons and 4.inv()
                session.movePointer(0, 0, heldButtons)
            }
        }
    }
}

/** A captured mouse event: relative motion, buttons and the wheel. */
private fun handleCapturedMouse(session: DisplaySession, event: MotionEvent) {
    val buttons = event.buttonState
    val mask = (if (buttons and MotionEvent.BUTTON_PRIMARY != 0) 1 else 0) or
        (if (buttons and MotionEvent.BUTTON_TERTIARY != 0) 2 else 0) or
        (if (buttons and MotionEvent.BUTTON_SECONDARY != 0) 4 else 0)
    when (event.actionMasked) {
        MotionEvent.ACTION_SCROLL -> {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) session.scroll(session.pointerX, session.pointerY, if (v > 0) 1 else -1)
        }
        else -> {
            // A captured mouse reports how far it moved, not where it is.
            val relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
            val dx = if (relative) event.x else event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val dy = if (relative) event.y else event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            session.movePointer(dx.toInt(), dy.toInt(), mask)
        }
    }
}

/**
 * Trackpad gestures, like a laptop's: slide to move the pointer, tap to
 * click, two-finger tap to right-click, two fingers to scroll, and hold
 * before sliding to drag.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackpadGestures(
    session: DisplaySession,
    heldButtons: () -> Int,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Mouse) return@awaitEachGesture
        val start = down.uptimeMillis
        var moved = 0f
        var fingers = 1
        var dragging = false
        var scrollTravel = 0f
        // Carries the fractions of a pixel between events.
        var carryX = 0f
        var carryY = 0f
        // The trackpad covers the screen about one and a half times.
        val speed = 1.5f * session.width / size.width.coerceAtLeast(1)

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            fingers = maxOf(fingers, pressed.size)
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                // Two fingers: scroll by the vertical movement.
                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                moved += abs(dy)
                scrollTravel += dy
                val steps = (scrollTravel / 30f).toInt()
                if (steps != 0) {
                    scrollTravel -= steps * 30f
                    // Fingers moving down pull the content down, as on a phone.
                    session.scroll(session.pointerX, session.pointerY, steps)
                }
            } else {
                val delta: Offset = pressed[0].positionChange()
                moved += hypot(delta.x, delta.y)
                // Holding still before moving starts a drag.
                if (!dragging && moved < 12f && event.changes[0].uptimeMillis - start > 450) {
                    dragging = true
                    session.movePointer(0, 0, heldButtons() or 1)
                }
                // Faster movement goes further, as on any trackpad.
                val accel = 1f + minOf(hypot(delta.x, delta.y) / 25f, 1.5f)
                carryX += delta.x * speed * accel
                carryY += delta.y * speed * accel
                val ix = carryX.toInt()
                val iy = carryY.toInt()
                if (ix != 0 || iy != 0) {
                    carryX -= ix
                    carryY -= iy
                    session.movePointer(ix, iy, heldButtons() or (if (dragging) 1 else 0))
                }
            }
            event.changes.forEach { it.consume() }
        }

        val held = heldButtons()
        when {
            dragging -> session.movePointer(0, 0, held)
            moved < 12f && fingers >= 2 -> {
                session.movePointer(0, 0, held or 4)
                session.movePointer(0, 0, held)
            }
            moved < 12f -> {
                session.movePointer(0, 0, held or 1)
                session.movePointer(0, 0, held)
            }
        }
    }
}

@Composable
private fun MouseButton(label: String, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(if (down) Color(0xFF3A3A3A) else Color(0xFF2B2B2B))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    onPress(true)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    down = false
                    onPress(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

@Composable
private fun MouseChip(captured: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (captured) UbuntuPalette.UbuntuOrange else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Mouse, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (captured) "Mouse on monitor · tap to release" else "Use a mouse",
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TrackpadBar(connected: Boolean, actions: DisplayActions, onShowOnPhone: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(UbuntuPalette.DarkSurface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onShowLauncher) {
            Icon(Icons.Default.Apps, contentDescription = "Apps", tint = Color.White)
        }
        StatusDot(connected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Ubuntu", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("On your monitor", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
        IconButton(onClick = actions.onOpenTerminal) {
            Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = Color.White)
        }
        IconButton(onClick = actions.onShowKeyboard) {
            Icon(Icons.Default.Keyboard, contentDescription = "Keyboard", tint = Color.White)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuEntry("Show on the phone", Icons.Default.PhoneAndroid) { menu = false; onShowOnPhone() }
                MenuEntry("Files", Icons.Default.Folder) { menu = false; actions.onOpenFiles() }
                MenuEntry("Ubuntu Software", Icons.Default.ShoppingBag) { menu = false; actions.onOpenStore() }
                MenuEntry("Reconnect display", Icons.Default.Refresh) { menu = false; actions.onReconnect() }
            }
        }
    }
}
