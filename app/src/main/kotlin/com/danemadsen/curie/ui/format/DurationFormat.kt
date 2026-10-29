package com.danemadsen.curie.ui.format

import java.util.Locale

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val MILLIS_PER_SECOND = 1_000L

/** A clip's length or position: "0:07", "12:34", "1:02:03". Negative times count as zero. */
fun formatClipDuration(millis: Long, locale: Locale = Locale.getDefault()): String {
    val totalSeconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0) {
        String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(locale, "%d:%02d", minutes, seconds)
    }
}

/** A call's running time as a phone shows it: "00:07", "12:34", "1:02:03". */
fun formatCallDuration(seconds: Long, locale: Locale = Locale.getDefault()): String {
    val totalSeconds = seconds.coerceAtLeast(0L)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val remainingSeconds = totalSeconds % SECONDS_PER_MINUTE

    return if (hours > 0) {
        String.format(locale, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(locale, "%02d:%02d", minutes, remainingSeconds)
    }
}
