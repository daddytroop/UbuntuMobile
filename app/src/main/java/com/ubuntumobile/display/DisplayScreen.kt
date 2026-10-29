package com.ubuntumobile.display

import android.view.KeyEvent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette

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
    modifier: Modifier = Modifier,
) {
    var frameTick by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        started = session.start()
        session.onFrame = { frameTick++ }
        session.refresh()
    }

    val bmp = session.bitmap

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Linux Display",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { session.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !started -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = UbuntuPalette.UbuntuOrange)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            session.error ?: "Starting the X displayâ€¦",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                    }
                }

                bmp == null -> {
                    Text("No framebuffer yet", color = Color.White.copy(alpha = 0.7f))
                }

                else -> {
                    // Work out the scale factor up front so that pointer
                    // coordinates can be mapped back to framebuffer pixels.
                    val cfg = LocalConfiguration.current
                    val density = LocalDensity.current
                    val boxW = with(density) { cfg.screenWidthDp.dp.toPx() }
                    val boxH = with(density) { cfg.screenHeightDp.dp.toPx() }
                    val scale = remember(bmp.width, bmp.height, boxW, boxH) {
                        minOf(boxW / bmp.width, boxH / bmp.height)
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        // A tap is a left click.
                                        val x = (offset.x / scale).toInt()
                                        val y = (offset.y / scale).toInt()
                                        session.pointer(x, y, 1)
                                        session.pointer(x, y, 0)
                                    },
                                    onDoubleTap = { offset ->
                                        val x = (offset.x / scale).toInt()
                                        val y = (offset.y / scale).toInt()
                                        session.pointer(x, y, 1)
                                        session.pointer(x, y, 0)
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val x = (offset.x / scale).toInt()
                                        val y = (offset.y / scale).toInt()
                                        session.pointer(x, y, 1)
                                    },
                                    onDrag = { change, _ ->
                                        val x = (change.position.x / scale).toInt()
                                        val y = (change.position.y / scale).toInt()
                                        session.pointer(x, y, 1)
                                    },
                                    onDragEnd = {
                                        session.pointer(0, 0, 0)
                                    },
                                    onDragCancel = {
                                        session.pointer(0, 0, 0)
                                    },
                                )
                            },
                    ) {
                        // `frameTick` forces a redraw when new pixels arrive.
                        @Suppress("UNUSED_EXPRESSION")
                        frameTick
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (bmp.width * scale).toInt(),
                                (bmp.height * scale).toInt(),
                            ),
                        )
                    }
                }
            }
        }

        // Status strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (session.connected) {
                    "${session.width}x${session.height}  Â·  ${session.bitmap?.width ?: 0}x${session.bitmap?.height ?: 0}"
                } else {
                    "disconnected"
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
            Text(
                "Touch = mouse Â· use the keyboard for keys",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
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
    else -> if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        // Latin-1 letters map to the same code point.
        keyEventToUnicode(keyCode)?.let { it.code }
    } else {
        null
    }
}

private fun keyEventToUnicode(keyCode: Int): Char? {
    val map = mapOf(
        KeyEvent.KEYCODE_0 to '0', KeyEvent.KEYCODE_1 to '1', KeyEvent.KEYCODE_2 to '2',
        KeyEvent.KEYCODE_3 to '3', KeyEvent.KEYCODE_4 to '4', KeyEvent.KEYCODE_5 to '5',
        KeyEvent.KEYCODE_6 to '6', KeyEvent.KEYCODE_7 to '7', KeyEvent.KEYCODE_8 to '8',
        KeyEvent.KEYCODE_9 to '9',
    )
    map[keyCode]?.let { return it }
    if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        return ('A' + (keyCode - KeyEvent.KEYCODE_A))
    }
    return null
}
