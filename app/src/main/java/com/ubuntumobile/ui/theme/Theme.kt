package com.ubuntumobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private fun lightColors() = lightColorScheme(
    primary = UbuntuPalette.UbuntuOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3B0900),
    secondary = UbuntuPalette.Aubergine,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD6F7),
    onSecondaryContainer = Color(0xFF2C072A),
    tertiary = UbuntuPalette.UbuntuOrangeLight,
    onTertiary = Color(0xFF3B0900),
    background = UbuntuPalette.LightBackground,
    onBackground = UbuntuPalette.LightOnSurface,
    surface = UbuntuPalette.LightSurface,
    onSurface = UbuntuPalette.LightOnSurface,
    surfaceVariant = UbuntuPalette.LightSurfaceVariant,
    onSurfaceVariant = UbuntuPalette.LightOnSurfaceVariant,
    outline = UbuntuPalette.LightOutline,
    outlineVariant = Color(0xFFDDDAD6),
    error = UbuntuPalette.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private fun darkColors() = darkColorScheme(
    primary = UbuntuPalette.UbuntuOrangeLight,
    onPrimary = Color(0xFF561F0D),
    primaryContainer = Color(0xFF7A2E12),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = UbuntuPalette.AubergineLight,
    onSecondary = Color(0xFF442042),
    secondaryContainer = Color(0xFF5D3757),
    onSecondaryContainer = Color(0xFFFFD6F7),
    tertiary = UbuntuPalette.UbuntuOrange,
    onTertiary = Color.White,
    background = UbuntuPalette.DarkBackground,
    onBackground = UbuntuPalette.DarkOnSurface,
    surface = UbuntuPalette.DarkSurface,
    onSurface = UbuntuPalette.DarkOnSurface,
    surfaceVariant = UbuntuPalette.DarkSurfaceVariant,
    onSurfaceVariant = UbuntuPalette.DarkOnSurfaceVariant,
    outline = UbuntuPalette.DarkOutline,
    outlineVariant = Color(0xFF3A3836),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val UbuntuTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun UbuntuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Built afresh when the accent changes (UbuntuPalette.accent).
    val accent = UbuntuPalette.accent
    val colors = androidx.compose.runtime.remember(darkTheme, accent) {
        if (darkTheme) darkColors() else lightColors()
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Every screen has a dark or aubergine bar behind the system
            // bars, so their icons are always drawn light.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            // The bars take the app's colour, which follows the accent.
            @Suppress("DEPRECATION")
            window.statusBarColor = UbuntuPalette.Aubergine.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = UbuntuPalette.AubergineDeep.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = UbuntuTypography,
        content = content,
    )
}

/** Shared spacing scale (GNOME uses an 8pt grid). */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}
