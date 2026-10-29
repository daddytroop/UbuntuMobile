package com.ubuntumobile.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Virtual gamepad state.
 *
 * This is the on-screen controller that will drive games running inside the
 * Ubuntu container (Steam, emulators, Flatpak titles). It reports an
 * Xbox-style layout: two analog sticks, a D-pad, four face buttons, two
 * shoulder buttons and two analog triggers.
 */
class GamepadState {
    var leftX by mutableStateOf(0f)
    var leftY by mutableStateOf(0f)
    var rightX by mutableStateOf(0f)
    var rightY by mutableStateOf(0f)
    var leftTrigger by mutableStateOf(0f)
    var rightTrigger by mutableStateOf(0f)

    var a by mutableStateOf(false)
    var b by mutableStateOf(false)
    var x by mutableStateOf(false)
    var y by mutableStateOf(false)
    var dpadUp by mutableStateOf(false)
    var dpadDown by mutableStateOf(false)
    var dpadLeft by mutableStateOf(false)
    var dpadRight by mutableStateOf(false)
    var l1 by mutableStateOf(false)
    var r1 by mutableStateOf(false)
    var start by mutableStateOf(false)
    var select by mutableStateOf(false)

    /** Buttons currently pressed, as a set of names. */
    fun pressedButtons(): Set<String> = buildSet {
        if (a) add("A")
        if (b) add("B")
        if (x) add("X")
        if (y) add("Y")
        if (dpadUp) add("DPAD_UP")
        if (dpadDown) add("DPAD_DOWN")
        if (dpadLeft) add("DPAD_LEFT")
        if (dpadRight) add("DPAD_RIGHT")
        if (l1) add("L1")
        if (r1) add("R1")
        if (start) add("START")
        if (select) add("SELECT")
    }

    /** Reset everything (used when the overlay is hidden). */
    fun reset() {
        leftX = 0f; leftY = 0f; rightX = 0f; rightY = 0f
        leftTrigger = 0f; rightTrigger = 0f
        a = false; b = false; x = false; y = false
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        l1 = false; r1 = false; start = false; select = false
    }
}

/**
 * The on-screen gamepad.
 *
 * Layout adapts to the device orientation: in landscape the sticks sit on
 * the left/right edges (like a console), in portrait they are stacked.
 */
@Composable
fun VirtualGamepad(
    state: GamepadState,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onHaptic: (String) -> Unit = {},
) {
    if (landscape) {
        Row(
            modifier = modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnalogStick(state.leftX, state.leftY, { x, y ->
                    state.leftX = x; state.leftY = y
                }, { state.leftX = 0f; state.leftY = 0f })
                Spacer(Modifier.height(12.dp))
                DPad(state)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FaceButtons(state, onHaptic)
                Spacer(Modifier.height(12.dp))
                AnalogStick(state.rightX, state.rightY, { x, y ->
                    state.rightX = x; state.rightY = y
                }, { state.rightX = 0f; state.rightY = 0f })
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnalogStick(state.leftX, state.leftY, { x, y ->
                    state.leftX = x; state.leftY = y
                }, { state.leftX = 0f; state.leftY = 0f })
                DPad(state)
                AnalogStick(state.rightX, state.rightY, { x, y ->
                    state.rightX = x; state.rightY = y
                }, { state.rightX = 0f; state.rightY = 0f })
            }
            FaceButtons(state, onHaptic)
        }
    }
}

/** A thumbstick that reports normalised (-1..1) displacement. */
@Composable
private fun AnalogStick(
    x: Float,
    y: Float,
    onChange: (Float, Float) -> Unit,
    onRelease: () -> Unit,
) {
    var center by remember { mutableStateOf<Offset?>(null) }
    val radius = 62.dp
    val density = LocalDensity.current
    val maxRadiusPx = with(density) { radius.toPx() }

    Box(
        modifier = Modifier
            .size(radius * 2)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { center = it },
                    onDragEnd = {
                        center = null
                        onRelease()
                    },
                    onDragCancel = {
                        center = null
                        onRelease()
                    },
                    onDrag = { change, _ ->
                        val c = center ?: change.position
                        val dx = change.position.x - c.x
                        val dy = change.position.y - c.y
                        val dist = hypot(dx, dy)
                        val clamped = min(dist, maxRadiusPx)
                        val nx = if (dist > 0f) dx / dist * clamped / maxRadiusPx else 0f
                        val ny = if (dist > 0f) dy / dist * clamped / maxRadiusPx else 0f
                        onChange(nx.coerceIn(-1f, 1f), ny.coerceIn(-1f, 1f))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.minDimension / 2f,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = size.minDimension / 2f,
                style = Stroke(width = 2f),
            )
        }
        Box(
            modifier = Modifier
                .size(radius)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val knob = r * 0.45f
                drawCircle(
                    color = UbuntuOrangeKnob,
                    radius = knob,
                    center = Offset(
                        size.width / 2f + x * (r - knob),
                        size.height / 2f + y * (r - knob),
                    ),
                )
            }
        }
    }
}

private val UbuntuOrangeKnob = Color(0xFFE95420)

/** Directional pad. */
@Composable
private fun DPad(state: GamepadState) {
    Box(modifier = Modifier.size(150.dp)) {
        PadButton(
            label = "UP", pressed = state.dpadUp,
            modifier = Modifier.align(Alignment.TopCenter).size(50.dp),
            onDown = { state.dpadUp = true }, onUp = { state.dpadUp = false },
        )
        PadButton(
            label = "DOWN", pressed = state.dpadDown,
            modifier = Modifier.align(Alignment.BottomCenter).size(50.dp),
            onDown = { state.dpadDown = true }, onUp = { state.dpadDown = false },
        )
        PadButton(
            label = "L", pressed = state.dpadLeft,
            modifier = Modifier.align(Alignment.CenterStart).size(50.dp),
            onDown = { state.dpadLeft = true }, onUp = { state.dpadLeft = false },
        )
        PadButton(
            label = "R", pressed = state.dpadRight,
            modifier = Modifier.align(Alignment.CenterEnd).size(50.dp),
            onDown = { state.dpadRight = true }, onUp = { state.dpadRight = false },
        )
    }
}

/** The four face buttons, arranged like an Xbox pad. */
@Composable
private fun FaceButtons(state: GamepadState, onHaptic: (String) -> Unit) {
    Box(modifier = Modifier.size(170.dp)) {
        FaceButton(
            label = "A", color = Color(0xFF5BAE4A), pressed = state.a,
            modifier = Modifier.align(Alignment.BottomCenter),
            onDown = { state.a = true; onHaptic("A") }, onUp = { state.a = false },
        )
        FaceButton(
            label = "B", color = Color(0xFFD43E3E), pressed = state.b,
            modifier = Modifier.align(Alignment.CenterEnd),
            onDown = { state.b = true; onHaptic("B") }, onUp = { state.b = false },
        )
        FaceButton(
            label = "X", color = Color(0xFF3B78D8), pressed = state.x,
            modifier = Modifier.align(Alignment.CenterStart),
            onDown = { state.x = true; onHaptic("X") }, onUp = { state.x = false },
        )
        FaceButton(
            label = "Y", color = Color(0xFFE8C02A), pressed = state.y,
            modifier = Modifier.align(Alignment.TopCenter),
            onDown = { state.y = true; onHaptic("Y") }, onUp = { state.y = false },
        )
    }
}

@Composable
private fun FaceButton(
    label: String,
    color: Color,
    modifier: Modifier,
    pressed: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (pressed) color else color.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        tryAwaitRelease()
                        onUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun PadButton(
    label: String,
    modifier: Modifier,
    pressed: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        tryAwaitRelease()
                        onUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label.takeLast(1), color = Color.White, fontSize = 14.sp)
    }
}
