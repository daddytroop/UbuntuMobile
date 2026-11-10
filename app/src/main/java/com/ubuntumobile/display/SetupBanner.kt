package com.ubuntumobile.display

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette

/** How far linx-setup.sh has got with installing what onboarding chose. */
data class SetupStatus(
    val step: Int,
    val total: Int,
    /** The step, e.g. "Firefox". */
    val title: String,
    /** What it is doing now, e.g. "Downloading 42%"; may be empty. */
    val detail: String,
    /** The whole setup, 0..1. */
    val fraction: Float,
    val failed: Boolean = false,
) {
    companion object {
        /**
         * Read the script's log ("STEP 2/5 Firefox") and the progress file,
         * where apt writes "dlstatus:pkg:percent:text" while downloading and
         * "pmstatus:pkg:percent:text" while installing, and Flatpak's
         * percentages arrive as "flatpak:42%".
         */
        fun parse(log: List<String>, progress: List<String>): SetupStatus? {
            val stepLine = log.lastOrNull { it.startsWith("STEP ") } ?: return null
            val head = stepLine.removePrefix("STEP ").split(" ", limit = 2)
            val nums = head[0].split("/")
            val step = nums.getOrNull(0)?.toIntOrNull() ?: return null
            val total = nums.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(step) ?: return null
            val title = head.getOrElse(1) { "" }

            var within = 0f
            var detail = ""
            val last = progress.lastOrNull { it.startsWith("dlstatus:") || it.startsWith("pmstatus:") || it.startsWith("flatpak:") }
            if (last != null) {
                val parts = last.split(":", limit = 4)
                when (parts[0]) {
                    "dlstatus" -> {
                        val pct = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                        within = pct / 200f
                        detail = "Downloading ${pct.toInt()}%"
                    }
                    "pmstatus" -> {
                        val pct = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                        within = 0.5f + pct / 200f
                        detail = parts.getOrNull(3)?.trim().orEmpty()
                    }
                    "flatpak" -> {
                        val pct = parts.getOrNull(1)?.removeSuffix("%")?.toFloatOrNull() ?: 0f
                        within = pct / 100f
                        detail = "Downloading ${pct.toInt()}%"
                    }
                }
            }
            val fraction = ((step - 1) + within.coerceIn(0f, 1f)) / total
            return SetupStatus(step, total, title, detail, fraction.coerceIn(0f, 1f))
        }
    }
}

/**
 * A small card at the top of the desktop while the apps chosen at setup
 * download and install, so it is plain that something is happening and
 * how far it is; or, when it failed, a way to try again.
 */
@Composable
fun SetupBanner(
    status: SetupStatus?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Hand the setup logs to another app (mail, chat); null hides the button. */
    onShareLog: (() -> Unit)? = null,
    /** Put the failure away until setup runs again; null hides the button. */
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = status != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val s = status ?: return@AnimatedVisibility
        Column(
            Modifier
                .padding(10.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21E1E1E))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (s.failed) Icons.Default.ErrorOutline else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (s.failed) UbuntuPalette.Warning else UbuntuPalette.UbuntuOrangeLight,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.failed) "Setup did not finish" else "Setting up · ${s.title}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Failed: what went wrong, from the step's own log.
                        if (s.failed) s.detail.ifBlank { "Check the connection and try again" }
                        else listOf("Step ${s.step} of ${s.total}", s.detail).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        maxLines = if (s.failed) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!s.failed) {
                    Text("${(s.fraction * 100).toInt()}%", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
            if (s.failed) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    if (onDismiss != null) BannerButton("Dismiss", onDismiss)
                    Spacer(Modifier.width(8.dp))
                    if (onShareLog != null) BannerButton("Share log", onShareLog)
                    Spacer(Modifier.width(8.dp))
                    BannerButton("Try again", onRetry)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { s.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = UbuntuPalette.UbuntuOrange,
                        trackColor = Color.White.copy(alpha = 0.15f),
                    )
                }
            }
        }
    }
}

/** "Opening Telegram..." over the desktop while an app starts. */
@Composable
fun OpeningPill(name: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = name != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF21E1E1E))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = UbuntuPalette.UbuntuOrangeLight,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text("Opening ${name.orEmpty()}…", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = UbuntuPalette.UbuntuOrangeLight,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
