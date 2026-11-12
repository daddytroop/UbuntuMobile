package com.ubuntumobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette

/*
 * Mobuntu's look, in one place: every screen of the app (apps, the store,
 * Settings) is setup's dark sky with the accent glowing through it, and on
 * it translucent cards, rows with an icon, and one kind of button. The
 * backdrop is drawn once and holds still (setup's moves): scrolling a list
 * over it costs nothing.
 */
object Ui {
    /** Text: titles, body, and the quiet kind. */
    val ink = Color.White
    val inkSoft = Color.White.copy(alpha = 0.72f)
    val inkFaint = Color.White.copy(alpha = 0.5f)

    /** Surfaces on the backdrop. */
    val card = Color.White.copy(alpha = 0.07f)
    val cardRaised = Color.White.copy(alpha = 0.12f)
    val edge = Color.White.copy(alpha = 0.10f)
    val divider = Color.White.copy(alpha = 0.07f)

    /** The accent chosen at setup. */
    val accent: Color get() = UbuntuPalette.UbuntuOrange

    val good = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)

    val cardShape = RoundedCornerShape(22.dp)
    val fieldShape = RoundedCornerShape(26.dp)

    /** Page gutters, and the widest a page's content gets on a tablet or monitor. */
    val gutter = 16.dp
    val maxWidth = 720.dp
}

/** Shrinks a little under the finger and springs back. */
fun Modifier.pressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * A list's top edge fades out over a few dp, so what scrolls under the
 * screen's bar leaves softly rather than at a hard line.
 */
fun Modifier.topFade(height: Dp = 14.dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = height.toPx()),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** A page fades and rises in when it opens. */
private fun Modifier.pageEnter(): Modifier = composed {
    val reduced = reducedMotion()
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 18.dp.toPx()
    }
}

/**
 * A screen of the app: the backdrop edge to edge, a bar with the way back
 * and a large title, then the page. [modifier] carries the system bars'
 * padding, which the backdrop is not given: it runs under them.
 */
@Composable
fun MobuntuScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backLabel: String = "Back",
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Ui.accent, animated = false, motes = 22)
        Column(modifier.fillMaxSize().pageEnter()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Ui.card)
                        .border(1.dp, Ui.edge, CircleShape)
                        .pressable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(backIcon, contentDescription = backLabel, tint = Ui.ink, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ui.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (subtitle != null) {
                        Text(subtitle, color = Ui.inkSoft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                trailing()
            }
            content()
        }
    }
}

/** A translucent card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Ui.cardShape)
            .background(Ui.card)
            .border(1.dp, Ui.edge, Ui.cardShape)
            .padding(padding),
        content = content,
    )
}

/** A group of rows under a small heading: the rows share one card. */
@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            color = Ui.inkFaint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        GlassCard(padding = 0.dp, content = content)
    }
}

/** A hairline between the rows of a [Group]. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 68.dp).height(1.dp).background(Ui.divider))
}

/** An icon on a tinted tile, as each row leads with. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ui.accent, size: Dp = 38.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A row of a [Group]: an icon, a title, a line of detail, and something at
 * the end (a switch, a button, a value). The whole row is the target when
 * [onClick] is given.
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    tint: Color = Ui.accent,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ui.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!detail.isNullOrBlank()) {
                Text(detail, color = Ui.inkSoft, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** The app's switch: the accent when on. */
@Composable
fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Ui.accent,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
            uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
            uncheckedBorderColor = Color.White.copy(alpha = 0.25f),
        ),
    )
}

/** The app's button: filled with the accent, or quiet glass. */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.08f)
        primary -> Ui.accent
        else -> Ui.cardRaised
    }
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .then(if (primary) Modifier else Modifier.border(1.dp, Ui.edge, RoundedCornerShape(22.dp)))
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (enabled) Color.White else Ui.inkFaint,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** One of a few choices side by side; the chosen one takes the accent. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.22f))
            .padding(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val fill by animateColorAsState(if (on) Ui.accent else Color.Transparent, tween(220), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fill)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = if (on) Color.White else Ui.inkSoft,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A filter chip: glass, or the accent when chosen. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Ui.accent else Ui.card, tween(200), label = "chip")
    Text(
        label,
        color = if (selected) Color.White else Ui.inkSoft,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .border(1.dp, if (selected) Color.Transparent else Ui.edge, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** The search field every screen shares. */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(50.dp)
            .clip(Ui.fieldShape)
            .background(Ui.cardRaised)
            .border(1.dp, Ui.edge, Ui.fieldShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Ui.inkSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = Ui.inkFaint, fontSize = 16.sp, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Ui.ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Ui.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Ui.inkSoft, modifier = Modifier.size(18.dp))
            }
        }
    }
}
