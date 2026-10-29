package com.danemadsen.curie.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Maid's text is React Native's default Roboto at the sizes its styles set. React Native keeps the
 * font padding on Android, so the styles do too: single-line text then measures as tall as in Maid.
 */
private fun maidTextStyle(fontSize: TextUnit, fontWeight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

/**
 * Maid's sizes mapped onto the Material roles:
 * - bodyMedium (14): message text, labels, the model pill, counters (React Native's default size)
 * - bodyLarge (16): inputs, chat titles, popover items, setting rows
 * - titleMedium (16 bold): names and settings group titles
 * - titleLarge (20 bold): page headers
 * - headlineSmall (18 bold): dialog messages
 * - labelLarge (16 bold): dialog buttons
 */
internal val CurieTypography = Typography(
    displayLarge = maidTextStyle(57.sp),
    displayMedium = maidTextStyle(45.sp),
    displaySmall = maidTextStyle(36.sp),
    headlineLarge = maidTextStyle(32.sp, FontWeight.Bold),
    headlineMedium = maidTextStyle(24.sp, FontWeight.Bold),
    headlineSmall = maidTextStyle(18.sp, FontWeight.Bold),
    titleLarge = maidTextStyle(20.sp, FontWeight.Bold),
    titleMedium = maidTextStyle(16.sp, FontWeight.Bold),
    titleSmall = maidTextStyle(14.sp, FontWeight.Bold),
    bodyLarge = maidTextStyle(16.sp),
    bodyMedium = maidTextStyle(14.sp),
    bodySmall = maidTextStyle(12.sp),
    labelLarge = maidTextStyle(16.sp, FontWeight.Bold),
    labelMedium = maidTextStyle(14.sp),
    labelSmall = maidTextStyle(12.sp),
)
