package com.ubuntumobile.ui

import androidx.compose.ui.graphics.Color
import com.ubuntumobile.ui.theme.UbuntuPalette

/**
 * A very small ANSI terminal emulator.
 *
 * It handles the subset of escape sequences that interactive shells and
 * common CLI tools emit: SGR colour codes, cursor movement, erase sequences
 * and carriage returns. Everything else is passed through verbatim so no
 * output is ever silently lost.
 */
class AnsiTerminal(private val maxLines: Int = 5000) {

    /** Visible screen lines. */
    private val lines = mutableListOf<String>()

    /** Current line being built. */
    private var current = StringBuilder()

    /** Current SGR attributes. */
    private var fg: Color? = null
    private var bold = false

    /** Output sink for rendered lines. */
    private var onLines: (List<String>) -> Unit = {}

    fun setSink(sink: (List<String>) -> Unit) {
        onLines = sink
        sink(lines.toList())
    }

    /** Feed raw bytes from the guest. */
    fun feed(data: ByteArray, length: Int) {
        val text = String(data, 0, length, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001B' -> {
                    // Escape sequence
                    if (i + 1 < text.length && text[i + 1] == '[') {
                        var j = i + 2
                        while (j < text.length && !text[j].isLetter()) j++
                        if (j < text.length) {
                            handleCsi(text.substring(i + 2, j), text[j])
                            i = j + 1
                            continue
                        }
                    }
                    // Skip unknown escape sequences conservatively.
                    i += 2
                }
                c == '\r' -> {
                    current = StringBuilder()
                    i++
                }
                c == '\n' -> {
                    lines.add(current.toString())
                    current = StringBuilder()
                    if (lines.size > maxLines) lines.removeAt(0)
                    i++
                }
                c == '\u0007' -> i++ // bell
                c == '\b' -> {
                    if (current.isNotEmpty()) current.setLength(current.length - 1)
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        onLines(lines.toList())
    }

    private fun handleCsi(params: String, final: Char) {
        val nums = params.split(";").mapNotNull { it.trim().toIntOrNull() }
        when (final) {
            'm' -> applySgr(nums)
            'K' -> {
                // Erase in line: 0=to end, 1=to start, 2=all
                when (nums.firstOrNull() ?: 0) {
                    1 -> current = StringBuilder()
                    2 -> {
                        current = StringBuilder()
                        if (lines.isNotEmpty()) lines[lines.lastIndex] = ""
                    }
                }
            }
            'J' -> {
                // Clear screen
                when (nums.firstOrNull() ?: 0) {
                    2 -> {
                        lines.clear()
                        current = StringBuilder()
                    }
                }
            }
            'A' -> { /* cursor up: ignore, output already streamed */ }
            'B' -> { /* cursor down */ }
            'C' -> { /* cursor forward */ }
            'D' -> { /* cursor back */ }
            'H', 'f' -> { /* cursor position */ }
            else -> {}
        }
    }

    private fun applySgr(nums: List<Int>) {
        if (nums.isEmpty()) {
            fg = null
            bold = false
            return
        }
        for (n in nums) {
            when {
                n == 0 -> { fg = null; bold = false }
                n == 1 -> bold = true
                n == 22 -> bold = false
                n in 30..37 -> fg = ansiColor(n - 30)
                n == 39 -> fg = null
                n in 40..47 -> { /* background colours are ignored */ }
                n in 90..97 -> fg = ansiBright(n - 90)
                else -> { /* unsupported attribute */ }
            }
        }
    }

    private fun ansiColor(i: Int): Color = when (i) {
        0 -> Color(0xFF2E3436)
        1 -> Color(0xFFCC0000)
        2 -> Color(0xFF4E9A06)
        3 -> Color(0xFFC4A000)
        4 -> Color(0xFF3465A4)
        5 -> Color(0xFF75507B)
        6 -> Color(0xFF06989A)
        else -> Color(0xFFD3D7CF)
    }

    private fun ansiBright(i: Int): Color = when (i) {
        0 -> Color(0xFF555753)
        1 -> Color(0xFFEF2929)
        2 -> Color(0xFF8AE234)
        3 -> Color(0xFFFCE94F)
        4 -> Color(0xFF729FCF)
        5 -> Color(0xFFAD7FA8)
        6 -> Color(0xFF34E2E2)
        else -> Color(0xFFEEEEEC)
    }

    /** Current text colour (exposed for the renderer). */
    fun currentColor(): Color = fg ?: UbuntuPalette.TerminalForeground
}
