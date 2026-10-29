package com.danemadsen.curie.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DurationFormatTest {
    @Test
    fun formatsClipLengths() {
        assertEquals("0:00", formatClipDuration(0L, Locale.US))
        assertEquals("0:07", formatClipDuration(7_999L, Locale.US))
        assertEquals("1:05", formatClipDuration(65_000L, Locale.US))
        assertEquals("59:59", formatClipDuration(3_599_000L, Locale.US))
        assertEquals("1:02:03", formatClipDuration(3_723_000L, Locale.US))
    }

    @Test
    fun clipLengthsAreNeverNegative() {
        assertEquals("0:00", formatClipDuration(-5_000L, Locale.US))
    }

    @Test
    fun formatsCallTimesLikeAPhone() {
        assertEquals("00:00", formatCallDuration(0L, Locale.US))
        assertEquals("00:07", formatCallDuration(7L, Locale.US))
        assertEquals("01:23", formatCallDuration(83L, Locale.US))
        assertEquals("59:59", formatCallDuration(3_599L, Locale.US))
        assertEquals("1:02:03", formatCallDuration(3_723L, Locale.US))
        assertEquals("00:00", formatCallDuration(-1L, Locale.US))
    }
}
