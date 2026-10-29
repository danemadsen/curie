package com.danemadsen.curie.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.CorePalette
import com.materialkolor.palettes.TonalPalette

private const val OPAQUE_ALPHA = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF
private const val SURFACE_DARKENING = 0x00101010
private const val SURFACE_VARIANT_DARKENING = 0x00303030

/**
 * The colour roles of Maid's createColorScheme() (utilities/color-scheme.ts) as opaque ARGB.
 *
 * Maid takes the light or dark scheme of material-color-utilities' themeFromSourceColor(), which is
 * the legacy Material 3 scheme built from CorePalette.of(seed) (not a DynamicScheme such as
 * TonalSpot: that one has other palette chromas and surface tones), and darkens two roles: the
 * surface by 0x101010 and the surface variant by 0x303030.
 */
data class MaidColors(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val shadow: Int,
    val scrim: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
)

/**
 * Maid's createColorScheme(seed, brightness). The seed's alpha is ignored, as Maid's hex parsing
 * drops it.
 */
fun maidColors(seedArgb: Int, isDark: Boolean): MaidColors {
    val palette = CorePalette.of(seedArgb or OPAQUE_ALPHA)

    return if (isDark) darkMaidColors(palette) else lightMaidColors(palette)
}

private fun lightMaidColors(palette: CorePalette): MaidColors {
    val primary = palette.a1
    val secondary = palette.a2
    val tertiary = palette.a3
    val neutral = palette.n1
    val neutralVariant = palette.n2
    val error = palette.error

    return MaidColors(
        primary = primary.legacyTone(40),
        onPrimary = primary.legacyTone(100),
        primaryContainer = primary.legacyTone(90),
        onPrimaryContainer = primary.legacyTone(10),
        secondary = secondary.legacyTone(40),
        onSecondary = secondary.legacyTone(100),
        secondaryContainer = secondary.legacyTone(90),
        onSecondaryContainer = secondary.legacyTone(10),
        tertiary = tertiary.legacyTone(40),
        onTertiary = tertiary.legacyTone(100),
        tertiaryContainer = tertiary.legacyTone(90),
        onTertiaryContainer = tertiary.legacyTone(10),
        error = error.legacyTone(40),
        onError = error.legacyTone(100),
        errorContainer = error.legacyTone(90),
        onErrorContainer = error.legacyTone(10),
        surface = darken(neutral.legacyTone(99), SURFACE_DARKENING),
        onSurface = neutral.legacyTone(10),
        surfaceVariant = darken(neutralVariant.legacyTone(90), SURFACE_VARIANT_DARKENING),
        onSurfaceVariant = neutralVariant.legacyTone(30),
        outline = neutralVariant.legacyTone(50),
        outlineVariant = neutralVariant.legacyTone(80),
        shadow = neutral.legacyTone(0),
        scrim = neutral.legacyTone(0),
        inverseSurface = neutral.legacyTone(20),
        inverseOnSurface = neutral.legacyTone(95),
        inversePrimary = primary.legacyTone(80),
    )
}

private fun darkMaidColors(palette: CorePalette): MaidColors {
    val primary = palette.a1
    val secondary = palette.a2
    val tertiary = palette.a3
    val neutral = palette.n1
    val neutralVariant = palette.n2
    val error = palette.error

    return MaidColors(
        primary = primary.legacyTone(80),
        onPrimary = primary.legacyTone(20),
        primaryContainer = primary.legacyTone(30),
        onPrimaryContainer = primary.legacyTone(90),
        secondary = secondary.legacyTone(80),
        onSecondary = secondary.legacyTone(20),
        secondaryContainer = secondary.legacyTone(30),
        onSecondaryContainer = secondary.legacyTone(90),
        tertiary = tertiary.legacyTone(80),
        onTertiary = tertiary.legacyTone(20),
        tertiaryContainer = tertiary.legacyTone(30),
        onTertiaryContainer = tertiary.legacyTone(90),
        error = error.legacyTone(80),
        onError = error.legacyTone(20),
        errorContainer = error.legacyTone(30),
        onErrorContainer = error.legacyTone(80),
        surface = darken(neutral.legacyTone(10), SURFACE_DARKENING),
        onSurface = neutral.legacyTone(90),
        surfaceVariant = darken(neutralVariant.legacyTone(30), SURFACE_VARIANT_DARKENING),
        onSurfaceVariant = neutralVariant.legacyTone(80),
        outline = neutralVariant.legacyTone(60),
        outlineVariant = neutralVariant.legacyTone(30),
        shadow = neutral.legacyTone(0),
        scrim = neutral.legacyTone(0),
        inverseSurface = neutral.legacyTone(90),
        inverseOnSurface = neutral.legacyTone(20),
        inversePrimary = primary.legacyTone(40),
    )
}

/**
 * The palette's colour at [tone] as material-color-utilities 0.3.0 (Maid's version) computes it.
 * Newer TonalPalette.tone() implementations average tones 98 and 100 for tone 99 of yellow hues.
 */
private fun TonalPalette.legacyTone(tone: Int): Int {
    val color = Hct.from(hue, chroma, tone.toDouble())
    return color.toInt()
}

/** Maid subtracts [amount] from the ARGB integer and JavaScript's hex conversion drops the alpha. */
private fun darken(argb: Int, amount: Int): Int = OPAQUE_ALPHA or ((argb - amount) and RGB_MASK)

/**
 * The Material 3 scheme for Maid's roles. Maid draws every page on `surface` and every container
 * (inputs, pills, popovers, dialogs) on `surfaceVariant`, so the newer surface roles map onto those
 * two; the fixed-accent roles, which Maid lacks, keep the Material baseline.
 */
fun MaidColors.toColorScheme(isDark: Boolean): ColorScheme {
    val baseline = if (isDark) darkColorScheme() else lightColorScheme()
    val surfaceColor = Color(surface)
    val containerColor = Color(surfaceVariant)

    return baseline.copy(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary),
        secondary = Color(secondary),
        onSecondary = Color(onSecondary),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        onTertiary = Color(onTertiary),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = surfaceColor,
        onBackground = Color(onSurface),
        surface = surfaceColor,
        onSurface = Color(onSurface),
        surfaceVariant = containerColor,
        onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(primary),
        inverseSurface = Color(inverseSurface),
        inverseOnSurface = Color(inverseOnSurface),
        error = Color(error),
        onError = Color(onError),
        errorContainer = Color(errorContainer),
        onErrorContainer = Color(onErrorContainer),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        scrim = Color(scrim),
        surfaceBright = surfaceColor,
        surfaceDim = surfaceColor,
        surfaceContainerLowest = surfaceColor,
        surfaceContainerLow = surfaceColor,
        surfaceContainer = containerColor,
        surfaceContainerHigh = containerColor,
        surfaceContainerHighest = containerColor,
    )
}
