package com.ubuntumobile.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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

/** The colours a [PadStyle] gives the face buttons and the sticks' knobs. */
private class PadColors(val a: Color, val b: Color, val x: Color, val y: Color, val knob: Color)

private val LocalPadColors = compositionLocalOf {
    PadColors(Color(0xFF5BAE4A), Color(0xFFD43E3E), Color(0xFF3B78D8), Color(0xFFE8C02A), Color(0xFFE95420))
}

private fun padColors(style: PadStyle, accent: Color) = when (style) {
    PadStyle.CLASSIC -> PadColors(Color(0xFF5BAE4A), Color(0xFFD43E3E), Color(0xFF3B78D8), Color(0xFFE8C02A), accent)
    PadStyle.MONO -> Color(0xFF9A9A9A).let { PadColors(it, it, it, it, Color(0xFFDDDDDD)) }
    PadStyle.ACCENT -> PadColors(accent, accent, accent, accent, accent)
}

/**
 * Where each part of the pad sits before the user moves it: an edge or
 * corner of the screen and a distance from it, in dp. In landscape the
 * left hand gets the left stick and the D-pad, the right hand the face
 * buttons and the right stick; in portrait they all sit in the lower half,
 * under the game.
 */
private fun homes(landscape: Boolean): Map<String, Pair<Alignment, Pair<Float, Float>>> =
    if (landscape) mapOf(
        "lshoulder" to (Alignment.TopStart to (0f to 0f)),
        "rshoulder" to (Alignment.TopEnd to (0f to 0f)),
        "lstick" to (Alignment.BottomStart to (13f to -158f)),
        "dpad" to (Alignment.BottomStart to (0f to 0f)),
        "face" to (Alignment.BottomEnd to (0f to -132f)),
        "rstick" to (Alignment.BottomEnd to (23f to 0f)),
        "middle" to (Alignment.BottomCenter to (0f to 0f)),
    ) else mapOf(
        "lshoulder" to (Alignment.BottomStart to (0f to -330f)),
        "rshoulder" to (Alignment.BottomEnd to (0f to -330f)),
        "dpad" to (Alignment.BottomStart to (0f to -140f)),
        "face" to (Alignment.BottomEnd to (0f to -136f)),
        "lstick" to (Alignment.BottomStart to (0f to 0f)),
        "rstick" to (Alignment.BottomEnd to (0f to 0f)),
        "middle" to (Alignment.BottomCenter to (0f to -44f)),
    )

/**
 * The on-screen gamepad, drawn over the game.
 *
 * It is an overlay: the controls are see-through ([GamepadSettings.opacity])
 * and sit at the edges like a handheld's, and nothing but the controls
 * themselves takes touches, so the game stays visible - and touchable - in
 * between. Each part can be moved ([editing]: drag it), and the whole pad
 * resized and recoloured ([GamepadSettings]).
 */
@Composable
fun VirtualGamepad(
    state: GamepadState,
    landscape: Boolean,
    settings: GamepadSettings,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFE95420),
    /** Parts can be dragged to new places, and do not press. */
    editing: Boolean = false,
    onMoved: (part: String, dx: Float, dy: Float) -> Unit = { _, _, _ -> },
    onHaptic: (String) -> Unit = {},
) {
    val parts: Map<String, @Composable () -> Unit> = mapOf(
        "lstick" to {
            AnalogStick(state.leftX, state.leftY, { x, y ->
                state.leftX = x; state.leftY = y
            }, { state.leftX = 0f; state.leftY = 0f })
        },
        "rstick" to {
            AnalogStick(state.rightX, state.rightY, { x, y ->
                state.rightX = x; state.rightY = y
            }, { state.rightX = 0f; state.rightY = 0f })
        },
        "lshoulder" to {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderButton("LT", state.leftTrigger > 0f, { state.leftTrigger = 1f }, { state.leftTrigger = 0f })
                ShoulderButton("LB", state.l1, { state.l1 = true }, { state.l1 = false })
            }
        },
        "rshoulder" to {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderButton("RB", state.r1, { state.r1 = true }, { state.r1 = false })
                ShoulderButton("RT", state.rightTrigger > 0f, { state.rightTrigger = 1f }, { state.rightTrigger = 0f })
            }
        },
        "middle" to {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShoulderButton("SELECT", state.select, { state.select = true }, { state.select = false }, small = true)
                ShoulderButton("START", state.start, { state.start = true }, { state.start = false }, small = true)
            }
        },
        "dpad" to { DPad(state) },
        "face" to { FaceButtons(state, onHaptic) },
    )
    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(LocalPadColors provides padColors(settings.style, accent)) {
        Box(
            modifier
                .fillMaxSize()
                .alpha(if (editing) maxOf(settings.opacity, 0.85f) else settings.opacity)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            for ((name, home) in homes(landscape)) {
                val (align, base) = home
                val (dx, dy) = settings.offset(name)
                val origin = androidx.compose.ui.graphics.TransformOrigin(
                    if (align == Alignment.TopEnd || align == Alignment.BottomEnd) 1f
                    else if (align == Alignment.BottomCenter) 0.5f else 0f,
                    if (align == Alignment.TopStart || align == Alignment.TopEnd) 0f else 1f,
                )
                Box(
                    Modifier
                        .align(align)
                        .offset((base.first + dx).dp, (base.second + dy).dp)
                        .graphicsLayer(scaleX = settings.size, scaleY = settings.size, transformOrigin = origin),
                ) {
                    parts.getValue(name)()
                    if (editing) {
                        // Over the part while editing: it takes the drags,
                        // so nothing is pressed.
                        Box(
                            Modifier
                                .matchParentSize()
                                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .pointerInput(name) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        with(density) {
                                            onMoved(name, drag.x.toDp().value * settings.size, drag.y.toDp().value * settings.size)
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/** A shoulder button or trigger, or (small) Select and Start. */
@Composable
private fun ShoulderButton(label: String, pressed: Boolean, onDown: () -> Unit, onUp: () -> Unit, small: Boolean = false) {
    Box(
        modifier = Modifier
            .size(width = if (small) 64.dp else 72.dp, height = if (small) 30.dp else 40.dp)
            .clip(RoundedCornerShape(if (small) 15.dp else 12.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.35f))
            .border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(if (small) 15.dp else 12.dp))
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
        Text(label, color = Color.White, fontSize = if (small) 10.sp else 13.sp)
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
    val knobColor = LocalPadColors.current.knob
    val radius = 62.dp
    val density = LocalDensity.current
    val maxRadiusPx = with(density) { radius.toPx() }

    Box(
        modifier = Modifier
            .size(radius * 2)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.3f))
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
                    color = knobColor,
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


/** Directional pad. */
@Composable
private fun DPad(state: GamepadState) {
    Box(modifier = Modifier.size(150.dp)) {
        PadButton(
            label = "▲", pressed = state.dpadUp,
            modifier = Modifier.align(Alignment.TopCenter).size(50.dp),
            onDown = { state.dpadUp = true }, onUp = { state.dpadUp = false },
        )
        PadButton(
            label = "▼", pressed = state.dpadDown,
            modifier = Modifier.align(Alignment.BottomCenter).size(50.dp),
            onDown = { state.dpadDown = true }, onUp = { state.dpadDown = false },
        )
        PadButton(
            label = "◀", pressed = state.dpadLeft,
            modifier = Modifier.align(Alignment.CenterStart).size(50.dp),
            onDown = { state.dpadLeft = true }, onUp = { state.dpadLeft = false },
        )
        PadButton(
            label = "▶", pressed = state.dpadRight,
            modifier = Modifier.align(Alignment.CenterEnd).size(50.dp),
            onDown = { state.dpadRight = true }, onUp = { state.dpadRight = false },
        )
    }
}

/** The four face buttons, arranged like an Xbox pad. */
@Composable
private fun FaceButtons(state: GamepadState, onHaptic: (String) -> Unit) {
    val colors = LocalPadColors.current
    Box(modifier = Modifier.size(170.dp)) {
        FaceButton(
            label = "A", color = colors.a, pressed = state.a,
            modifier = Modifier.align(Alignment.BottomCenter),
            onDown = { state.a = true; onHaptic("A") }, onUp = { state.a = false },
        )
        FaceButton(
            label = "B", color = colors.b, pressed = state.b,
            modifier = Modifier.align(Alignment.CenterEnd),
            onDown = { state.b = true; onHaptic("B") }, onUp = { state.b = false },
        )
        FaceButton(
            label = "X", color = colors.x, pressed = state.x,
            modifier = Modifier.align(Alignment.CenterStart),
            onDown = { state.x = true; onHaptic("X") }, onUp = { state.x = false },
        )
        FaceButton(
            label = "Y", color = colors.y, pressed = state.y,
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
            .background(if (pressed) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.35f))
            .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
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
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}
