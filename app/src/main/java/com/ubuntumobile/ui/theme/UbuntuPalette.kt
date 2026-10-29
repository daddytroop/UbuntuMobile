package com.ubuntumobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Ubuntu 26.04 "Resolute Raccoon" palette.
 *
 * Ubuntu ships the Yaru theme on top of GNOME 50. The signature colours are
 * the aubergine "Ubuntu Purple" family plus the warm Yaru orange, with a
 * neutral grey scale for surfaces.
 */
object UbuntuPalette {

    // Brand
    val UbuntuOrange = Color(0xFFE95420)
    val UbuntuOrangeHover = Color(0xFFD9481A)
    val UbuntuOrangeLight = Color(0xFFFF8A52)

    val Aubergine = Color(0xFF77216F)
    val AubergineDark = Color(0xFF5C1B56)
    val AubergineLight = Color(0xFF9A5C93)

    // Light surfaces (Yaru light)
    val LightBackground = Color(0xFFF6F5F4)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEDEBE9)
    val LightOutline = Color(0xFFCBC9C6)
    val LightOnSurface = Color(0xFF1B1B1B)
    val LightOnSurfaceVariant = Color(0xFF57534E)

    // Dark surfaces (Yaru dark)
    val DarkBackground = Color(0xFF1C1B1A)
    val DarkSurface = Color(0xFF262523)
    val DarkSurfaceVariant = Color(0xFF383634)
    val DarkOutline = Color(0xFF4E4B48)
    val DarkOnSurface = Color(0xFFF2F0EE)
    val DarkOnSurfaceVariant = Color(0xFFBFBBB6)

    // Terminal
    val TerminalBackground = Color(0xFF300A24)
    val TerminalForeground = Color(0xFFF2F0EE)
    val TerminalGreen = Color(0xFF8AE234)
    val TerminalBlue = Color(0xFF729FCF)
    val TerminalYellow = Color(0xFFFCE94F)
    val TerminalRed = Color(0xFFF66151)
    val TerminalPurple = Color(0xFFAD7FA8)
    val TerminalCyan = Color(0xFF34E2E2)

    // Status
    val Success = Color(0xFF26A269)
    val Warning = Color(0xFFE9A820)
    val Error = Color(0xFFC01C28)
}
