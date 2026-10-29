package com.danemadsen.curie.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** Maid's theme modes: follow the system, or force light or dark colours. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Maid's default accent colour, the seed of the colour scheme. */
val DEFAULT_ACCENT_COLOR = Color(0xFF2196F3)

private val LocalAccentColor = staticCompositionLocalOf { DEFAULT_ACCENT_COLOR }
private val LocalUsesSystemColors = staticCompositionLocalOf { false }

/** Whether the phone has a system palette apps can take their colours from (Material You). */
val areSystemColorsAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Curie's look, ported from Maid: Maid's text sizes, TouchableOpacity press feedback and system
 * bar icons that follow the chosen brightness. The colours are the system's palette with
 * [useSystemColors] where the phone has one (Android 12 and later), else the Material scheme Maid
 * derives from [accentColor] (see [maidColors]). Pages are drawn on `surface` and containers on
 * `surfaceVariant` either way. Draw edge to edge: screens pad themselves for the system bars.
 *
 * [useSystemColors] defaults to false so that previews and tests get the same colours everywhere;
 * the app passes the user's setting.
 */
@Composable
fun CurieTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentColor: Color = DEFAULT_ACCENT_COLOR,
    useSystemColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = remember(accentColor, isDark, useSystemColors, context) {
        if (useSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            systemColorScheme(context, isDark)
        } else {
            val colors = maidColors(accentColor.toArgb(), isDark)
            colors.toColorScheme(isDark)
        }
    }

    SystemBarAppearanceEffect(useDarkIcons = !isDark)

    MaterialTheme(colorScheme = colorScheme, typography = CurieTypography) {
        CompositionLocalProvider(
            LocalIndication provides OpacityIndication.DEFAULT,
            LocalContentColor provides colorScheme.onSurface,
            LocalAccentColor provides accentColor,
            LocalUsesSystemColors provides useSystemColors,
            content = content,
        )
    }
}

/** The surrounding theme's colours in their dark variant, for screens that are always dark (the call). */
@Composable
fun DarkCurieTheme(content: @Composable () -> Unit) {
    CurieTheme(
        themeMode = ThemeMode.DARK,
        accentColor = LocalAccentColor.current,
        useSystemColors = LocalUsesSystemColors.current,
        content = content,
    )
}

/** The system's Material You scheme, from the wallpaper or the accent the user picked. */
@RequiresApi(Build.VERSION_CODES.S)
private fun systemColorScheme(context: Context, isDark: Boolean): ColorScheme =
    if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
