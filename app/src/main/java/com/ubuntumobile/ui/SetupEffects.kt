package com.ubuntumobile.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * The effects that make setup feel alive: a slowly moving glow behind
 * everything in the colour being picked, things rising into place one after
 * another, a logo that lands with a bounce, and confetti at the end.
 * All of it is drawn by Compose: nothing to download, and cheap enough for
 * any phone (one canvas, a few dozen shapes).
 */

/**
 * True when the phone is set to show no animations (Settings, Accessibility,
 * "Remove animations"): then nothing here moves, and nothing waits to appear.
 */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Milliseconds since this was first shown, every frame, until [untilMs]
 * (so an effect that has played out stops asking for frames). Stays at
 * [still] when animations are off.
 */
@Composable
private fun frameClock(untilMs: Long = Long.MAX_VALUE, still: Long = 0L): State<Long> {
    val reduced = reducedMotion()
    val now = remember { mutableLongStateOf(if (reduced) still else 0L) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (now.longValue < untilMs) withFrameMillis { now.longValue = it - start }
    }
    return now
}

private class Mote(val x: Float, val speed: Float, val size: Float, val phase: Float, val drift: Float)

/**
 * A dark aubergine sky with large soft glows of the accent and Ubuntu's
 * orange drifting across it, and specks of light rising slowly, like the
 * Ubuntu installer's background brought to life.
 */
@Composable
fun AuroraBackground(
    accent: Color,
    modifier: Modifier = Modifier,
    motes: Int = 36,
    /** False for the app's everyday screens: one still frame, drawn once. */
    animated: Boolean = true,
) {
    // Read while drawing, so each frame redraws and nothing recomposes.
    val clock = if (animated) frameClock(still = 40_000L) else remember { mutableLongStateOf(40_000L) }
    val specks = remember(motes) {
        val r = Random(7)
        List(motes) { Mote(r.nextFloat(), 0.012f + r.nextFloat() * 0.03f, 0.6f + r.nextFloat() * 1.8f, r.nextFloat() * 6.28f, r.nextFloat() * 0.04f) }
    }
    val deep = lerp(accent, Color.Black, 0.88f)
    val mid = lerp(accent, Color(0xFF2C001E), 0.55f)
    val orange = Color(0xFFE95420)
    val plum = Color(0xFF77216F)
    Canvas(modifier.fillMaxSize()) {
        val t = clock.value / 1000f
        drawRect(Brush.verticalGradient(listOf(deep, lerp(deep, mid, 0.45f), deep)))
        val w = size.width
        val h = size.height
        val big = maxOf(w, h)
        // Each glow follows its own slow loop around the screen.
        fun glow(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
            drawCircle(
                Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        glow(accent, 0.55f, w * (0.25f + 0.2f * sin(t * 0.21f)), h * (0.22f + 0.1f * cos(t * 0.17f)), big * 0.55f)
        glow(plum, 0.6f, w * (0.8f + 0.15f * cos(t * 0.13f)), h * (0.55f + 0.15f * sin(t * 0.19f)), big * 0.6f)
        glow(orange, 0.35f, w * (0.35f + 0.25f * sin(t * 0.11f + 2f)), h * (0.92f + 0.06f * cos(t * 0.23f)), big * 0.5f)
        glow(lerp(accent, Color.White, 0.35f), 0.18f, w * (0.6f + 0.3f * cos(t * 0.09f + 1f)), h * (0.35f + 0.2f * sin(t * 0.07f)), big * 0.35f)
        // Specks of light, rising and twinkling.
        for (m in specks) {
            val y = 1.1f - ((t * m.speed + m.phase / 6.28f) % 1.2f)
            val x = m.x + sin(t * 0.5f + m.phase) * m.drift
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 1.7f + m.phase * 3f))
            val fade = (minOf(y, 1f - y) * 6f).coerceIn(0f, 1f)
            drawCircle(Color.White.copy(alpha = 0.55f * twinkle * fade), radius = m.size.dp.toPx(), center = Offset(x * w, y * h))
        }
    }
}

/**
 * Things on a page come in one after another, rising and fading in. Each
 * composable that calls [riseIn] takes the next place in the line.
 */
class Stagger {
    private var next = 0
    fun take(): Int = next++
}

val LocalStagger = compositionLocalOf<Stagger?> { null }

fun Modifier.riseIn(): Modifier = composed {
    val stagger = LocalStagger.current
    val reduced = reducedMotion()
    val index = remember { stagger?.take() ?: 0 }
    val shown = remember { Animatable(if (stagger == null || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (shown.value < 1f) {
            delay(60L * index.coerceAtMost(10))
            shown.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
        }
    }
    graphicsLayer {
        val v = shown.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 48.dp.toPx()
        val s = 0.96f + 0.04f * v
        scaleX = s
        scaleY = s
    }
}

/**
 * Mobuntu's mark (the circle and dot of [UbuntuLogo]), landing with a
 * bounce, with a ring of light turning around it, two sparks riding the
 * ring and a soft halo breathing behind.
 */
@Composable
fun AnimatedLogo(size: Dp, accent: Color, spinning: Boolean = true) {
    val reduced = reducedMotion()
    val landing = remember { Animatable(if (reduced) 1f else 0.2f) }
    LaunchedEffect(Unit) {
        landing.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    val loop = rememberInfiniteTransition(label = "logo")
    val turn by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "turn")
    val breathe by loop.animateFloat(
        0.85f, 1.12f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(
        Modifier.size(size * 1.9f).graphicsLayer {
            scaleX = landing.value
            scaleY = landing.value
            alpha = landing.value.coerceIn(0f, 1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.toPx() / 2f
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.15f), Color.Transparent),
                    center = c,
                    radius = r * 1.9f * breathe,
                ),
                radius = r * 1.9f * breathe,
                center = c,
            )
            if (spinning && !reduced) {
                rotate(turn, c) {
                    drawArc(
                        Brush.sweepGradient(
                            listOf(Color.Transparent, accent.copy(alpha = 0.2f), Color.White, Color.Transparent),
                            center = c,
                        ),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(c.x - r * 1.3f, c.y - r * 1.3f),
                        size = Size(r * 2.6f, r * 2.6f),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            // The mark: a disc in the accent with a white dot at its heart.
            drawCircle(
                Brush.linearGradient(
                    listOf(lerp(accent, Color.White, 0.25f), accent, lerp(accent, Color.Black, 0.2f)),
                    start = Offset(c.x - r, c.y - r),
                    end = Offset(c.x + r, c.y + r),
                ),
                radius = r,
                center = c,
            )
            drawCircle(Color.White, radius = r * 0.42f * (0.94f + 0.06f * breathe), center = c)
            // Two sparks riding the ring of light.
            if (spinning && !reduced) {
                for (i in 0 until 2) {
                    val a = (turn + i * 180f) * PI.toFloat() / 180f
                    val dot = Offset(c.x + cos(a) * r * 1.3f, c.y + sin(a) * r * 1.3f)
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = 2.5.dp.toPx(), center = dot)
                }
            }
        }
    }
}

private class Confetto(
    val x: Float,
    val vx: Float,
    val vy: Float,
    val spin: Float,
    val color: Color,
    val w: Float,
    val h: Float,
)

/** A burst of confetti from the top, falling through once. */
@Composable
fun Confetti(accent: Color, modifier: Modifier = Modifier) {
    val pieces = remember {
        val r = Random(System.nanoTime())
        val colors = listOf(accent, Color(0xFFE95420), Color.White, Color(0xFFF6C344), Color(0xFF77216F), lerp(accent, Color.White, 0.5f))
        List(90) {
            Confetto(
                x = r.nextFloat(),
                vx = (r.nextFloat() - 0.5f) * 0.35f,
                vy = 0.15f + r.nextFloat() * 0.45f,
                spin = (r.nextFloat() - 0.5f) * 720f,
                color = colors[r.nextInt(colors.size)],
                w = 5f + r.nextFloat() * 6f,
                h = 3f + r.nextFloat() * 4f,
            )
        }
    }
    if (reducedMotion()) return
    val clock = frameClock(untilMs = 4600L)
    Canvas(modifier.fillMaxSize()) {
        val t = clock.value / 1000f
        if (t > 4.5f) return@Canvas
        val fade = (1f - (t - 3f) / 1.5f).coerceIn(0f, 1f)
        for (p in pieces) {
            val x = (p.x + p.vx * t) * size.width
            val y = (-0.08f + p.vy * t + 0.22f * t * t) * size.height
            if (y > size.height) continue
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(
                    p.color.copy(alpha = fade),
                    topLeft = Offset(x - p.w.dp.toPx() / 2, y - p.h.dp.toPx() / 2),
                    size = Size(p.w.dp.toPx(), p.h.dp.toPx() * (0.4f + 0.6f * kotlin.math.abs(cos(t * 6f + p.spin)))),
                )
            }
        }
    }
}

/** A light sweeping across, for the main button. */
@Composable
fun shimmerOffset(): Float {
    val loop = rememberInfiniteTransition(label = "shimmer")
    val x by loop.animateFloat(
        -1f, 2f,
        infiniteRepeatable(tween(2400, delayMillis = 600, easing = FastOutSlowInEasing)),
        label = "x",
    )
    return x
}

/** The accent the setup screens use now (follows the colour being picked). */
val setupAccent: Color get() = UbuntuPalette.UbuntuOrange
