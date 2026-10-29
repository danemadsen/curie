package com.danemadsen.curie.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Compares [maidColors] with Maid's createColorScheme(). The expected values were printed by
 * Maid's utilities/color-scheme.ts running on @material/material-color-utilities 0.3.0 (the
 * package and version in Maid's yarn.lock), one hex colour per role in [ROLE_ORDER].
 */
class MaidColorSchemeTest {
    @Test
    fun matchesMaidsColorSchemes() {
        for (reference in REFERENCES) {
            val colors = maidColors(reference.seed, reference.isDark)
            val actual = colors.toHexList()
            val expected = reference.roles.split(" ")

            for (index in ROLE_ORDER.indices) {
                val message = "${ROLE_ORDER[index]} of ${reference.name}"
                assertEquals(message, expected[index], actual[index])
            }
        }
    }

    @Test
    fun colorsAreOpaque() {
        for (reference in REFERENCES) {
            val colors = maidColors(reference.seed, reference.isDark)
            val opaque = colors.toArgbList().all { argb -> argb ushr 24 == 0xFF }
            assertEquals(reference.name, true, opaque)
        }
    }

    @Test
    fun seedAlphaIsIgnored() {
        val opaqueSeed = maidColors(0xFF2196F3.toInt(), isDark = false)
        val translucentSeed = maidColors(0x002196F3, isDark = false)

        assertEquals(opaqueSeed, translucentSeed)
    }

    @Test
    fun materialSchemeUsesMaidsSurfaces() {
        val colors = maidColors(0xFF2196F3.toInt(), isDark = true)
        val scheme = colors.toColorScheme(isDark = true)

        assertEquals(Color(colors.primary), scheme.primary)
        assertEquals(Color(colors.onPrimaryContainer), scheme.onPrimaryContainer)
        assertEquals(Color(colors.surface), scheme.surface)
        assertEquals(Color(colors.surface), scheme.background)
        assertEquals(Color(colors.surfaceVariant), scheme.surfaceVariant)
        assertEquals(Color(colors.surfaceVariant), scheme.surfaceContainerHighest)
        assertEquals(Color(colors.outline), scheme.outline)
        assertEquals(Color(colors.outlineVariant), scheme.outlineVariant)
    }

    private fun MaidColors.toArgbList(): List<Int> = listOf(
        primary, onPrimary, primaryContainer, onPrimaryContainer,
        secondary, onSecondary, secondaryContainer, onSecondaryContainer,
        tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
        error, onError, errorContainer, onErrorContainer,
        surface, onSurface, surfaceVariant, onSurfaceVariant,
        outline, outlineVariant, shadow, scrim,
        inverseSurface, inverseOnSurface, inversePrimary,
    )

    private fun MaidColors.toHexList(): List<String> =
        toArgbList().map { argb -> String.format("%06X", argb and 0xFFFFFF) }

    private class Reference(val name: String, val seed: Int, val isDark: Boolean, val roles: String)

    private companion object {
        val ROLE_ORDER = listOf(
            "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
            "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
            "error", "onError", "errorContainer", "onErrorContainer",
            "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
            "outline", "outlineVariant", "shadow", "scrim",
            "inverseSurface", "inverseOnSurface", "inversePrimary",
        )

        fun reference(seed: String, brightness: String, roles: String): Reference {
            val argb = seed.removePrefix("#").toLong(16).toInt()
            return Reference("$seed $brightness", argb, brightness == "dark", roles)
        }

        val REFERENCES = listOf(
            reference(
                "#2196F3", "light",
                "0061A4 FFFFFF D1E4FF 001D36 535F70 FFFFFF D7E3F7 101C2B 6B5778 " +
                    "FFFFFF F2DAFF 251431 BA1A1A FFFFFF FFDAD6 410002 EDECEF 1A1C1E " +
                    "AFB2BB 43474E 73777F C3C7CF 000000 000000 2F3033 F1F0F4 9ECAFF",
            ),
            reference(
                "#2196F3", "dark",
                "9ECAFF 003258 00497D D1E4FF BBC7DB 253140 3B4858 D7E3F7 D6BEE4 " +
                    "3B2948 523F5F F2DAFF FFB4AB 690005 93000A FFB4AB 0A0C0E E2E2E6 " +
                    "13171E C3C7CF 8D9199 43474E 000000 000000 E2E2E6 2F3033 0061A4",
            ),
            reference(
                "#FFEB3B", "light",
                "695F00 FFFFFF F9E534 201C00 645F41 FFFFFF EBE3BD 1F1C05 406652 " +
                    "FFFFFF C2ECD3 002113 BA1A1A FFFFFF FFDAD6 410002 EFEBEF 1D1C16 " +
                    "B8B2A0 4A473A 7B7768 CBC6B5 000000 000000 32302A F5F0E7 DBC90A",
            ),
            reference(
                "#FFEB3B", "dark",
                "DBC90A 363100 4F4800 F9E534 CEC7A3 343117 4B472B EBE3BD A7D0B7 " +
                    "103726 294E3B C2ECD3 FFB4AB 690005 93000A FFB4AB 0D0C06 E7E2D9 " +
                    "1A170A CBC6B5 959181 4A473A 000000 000000 E7E2D9 32302A 695F00",
            ),
            reference(
                "#F44336", "light",
                "BB1614 FFFFFF FFDAD5 410001 775652 FFFFFF FFDAD5 2C1512 705C2E " +
                    "FFFFFF FCDFA6 261A00 BA1A1A FFFFFF FFDAD6 410002 EFEBEF 201A19 " +
                    "C5ADAA 534341 857370 D8C2BE 000000 000000 362F2E FBEEEC FFB4A9",
            ),
            reference(
                "#F44336", "dark",
                "FFB4A9 690002 930005 FFDAD5 E7BDB7 442926 5D3F3B FFDAD5 DFC38C " +
                    "3E2E04 574419 FCDFA6 FFB4AB 690005 93000A FFB4AB 100A09 EDE0DE " +
                    "231311 D8C2BE A08C89 534341 000000 000000 EDE0DE 362F2E BB1614",
            ),
            reference(
                "#4CAF50", "light",
                "006E1C FFFFFF 94F990 002204 52634F FFFFFF D5E8CF 111F0F 38656A " +
                    "FFFFFF BCEBF0 002023 BA1A1A FFFFFF FFDAD6 410002 ECEDE6 1A1C19 " +
                    "AEB5A8 424940 72796F C2C9BD 000000 000000 2F312D F0F1EB 78DC77",
            ),
            reference(
                "#4CAF50", "dark",
                "78DC77 00390A 005313 94F990 BACCB3 253423 3B4B38 D5E8CF A0CFD4 " +
                    "00363B 1F4D52 BCEBF0 FFB4AB 690005 93000A FFB4AB 0A0C09 E2E3DD " +
                    "121910 C2C9BD 8C9388 424940 000000 000000 E2E3DD 2F312D 006E1C",
            ),
            reference(
                "#9C27B0", "light",
                "9A25AE FFFFFF FFD6FE 35003F 6B586B FFFFFF F4DBF1 251626 82524A " +
                    "FFFFFF FFDAD4 33110C BA1A1A FFFFFF FFDAD6 410002 EFEBEF 1E1A1D " +
                    "BCAFB8 4D444C 7F747D D0C3CC 000000 000000 332F32 F7EEF3 F9ABFF",
            ),
            reference(
                "#9C27B0", "dark",
                "F9ABFF 570066 7B008F FFD6FE D7BFD5 3B2B3C 534153 F4DBF1 F6B8AD " +
                    "4C251F 673B34 FFDAD4 FFB4AB 690005 93000A FFB4AB 0E0A0D E9E0E4 " +
                    "1D141C D0C3CC 998D96 4D444C 000000 000000 E9E0E4 332F32 9A25AE",
            ),
            reference(
                "#607D8B", "light",
                "006783 FFFFFF BDE9FF 001F2A 4D616C FFFFFF D0E6F2 081E27 5D5B7D " +
                    "FFFFFF E3DFFF 191836 BA1A1A FFFFFF FFDAD6 410002 EBECEE 191C1E " +
                    "ACB4B9 40484C 70787D C0C8CD 000000 000000 2E3132 EFF1F3 65D3FF",
            ),
            reference(
                "#607D8B", "dark",
                "65D3FF 003546 004D64 BDE9FF B4CAD6 1F333C 354A53 D0E6F2 C6C2EA " +
                    "2E2D4D 454364 E3DFFF FFB4AB 690005 93000A FFB4AB 090C0E E1E2E4 " +
                    "10181C C0C8CD 8A9297 40484C 000000 000000 E1E2E4 2E3132 006783",
            ),
            reference(
                "#000000", "light",
                "984061 FFFFFF FFD9E2 3E001D 74565F FFFFFF FFD9E2 2B151C 7C5635 " +
                    "FFFFFF FFDCC1 2E1500 BA1A1A FFFFFF FFDAD6 410002 EFEBEF 201A1B " +
                    "C2ADB1 514347 837377 D5C2C6 000000 000000 352F30 FAEEEF FFB1C8",
            ),
            reference(
                "#000000", "dark",
                "FFB1C8 5E1133 7B2949 FFD9E2 E3BDC6 422931 5A3F47 FFD9E2 EFBD94 " +
                    "48290B 623F20 FFDCC1 FFB4AB 690005 93000A FFB4AB 100A0B EBE0E1 " +
                    "211317 D5C2C6 9E8C90 514347 000000 000000 EBE0E1 352F30 984061",
            ),
            reference(
                "#FF9800", "light",
                "8B5000 FFFFFF FFDCBE 2C1600 725A42 FFFFFF FFDCBE 291806 58633A " +
                    "FFFFFF DCE8B4 161E01 BA1A1A FFFFFF FFDAD6 410002 EFEBEF 201B16 " +
                    "C2AFA1 51453A 837468 D5C3B5 000000 000000 352F2B FAEFE7 FFB870",
            ),
            reference(
                "#FF9800", "dark",
                "FFB870 4A2800 693C00 FFDCBE E1C1A4 402C18 59422C FFDCBE C0CC9A " +
                    "2B3410 414B24 DCE8B4 FFB4AB 690005 93000A FFB4AB 100B06 EBE0D9 " +
                    "21150A D5C3B5 9D8E81 51453A 000000 000000 EBE0D9 352F2B 8B5000",
            ),
        )
    }
}
