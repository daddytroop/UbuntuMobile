package com.ubuntumobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.delay

/**
 * A terminal view backed by a real PTY inside the Ubuntu container.
 *
 * The screen renders the raw byte stream coming back from the guest shell
 * (including ANSI escape sequences, which the terminal emulator interprets
 * and strips before display).
 */
@Composable
fun TerminalScreen(
    lines: List<String>,
    onInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            delay(30)
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(UbuntuPalette.TerminalBackground),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = UbuntuPalette.TerminalForeground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    style = TextStyle(letterSpacing = 0.sp),
                )
            }
        }
    }
}
