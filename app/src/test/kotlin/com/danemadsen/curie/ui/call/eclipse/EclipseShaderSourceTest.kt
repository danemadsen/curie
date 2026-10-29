package com.danemadsen.curie.ui.call.eclipse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AGSL itself is compiled on a device (RuntimeShader is not available on the JVM); these tests guard the
 * parts that would otherwise only fail there: uniform names, generated constants and portability rules.
 */
class EclipseShaderSourceTest {
    private val source = EclipseShaderSource.SOURCE

    @Test
    fun declaresExactlyTheUniformsTheKotlinSideSets() {
        val uniformPattern = Regex("""uniform\s+\w+\s+(\w+)\s*;""")
        val matches = uniformPattern.findAll(source)
        val declared = matches.map { match -> match.groupValues[1] }

        assertEquals(EclipseShaderSource.UNIFORM_NAMES.sorted(), declared.toList().sorted())
    }

    @Test
    fun hasTheAgslEntryPoint() {
        assertTrue(source.contains("half4 main(float2 fragCoord)"))
    }

    @Test
    fun writesEveryConstantAsAPlainFiniteDecimal() {
        assertFalse(source.contains("NaN"))
        assertFalse(source.contains("Infinity"))
        assertTrue(source.contains("const float MOON_RADIUS_FRACTION = 0.2;"))
        assertTrue(source.contains("const float3 SKY = float3(0.00020, 0.00030, 0.00080);"))

        val exponentPattern = Regex("""\d[eE][-+]?\d""")
        val exponents = exponentPattern.findAll(source)
        val exponentTexts = exponents.map { match -> match.value }
        assertEquals(emptyList<String>(), exponentTexts.toList())
    }

    @Test
    fun theStreamerFieldTopIsTheHarmonicsLargestValue() {
        val expected = "const float STREAMER_FIELD_HIGH = ${CoronaLook.STREAMER_FIELD_HIGH};"

        assertTrue(source.contains(expected))
    }

    @Test
    fun bracketsAreBalancedOutsideComments() {
        val lines = source.lines()
        val code = lines.joinToString(separator = "\n") { line -> line.substringBefore("//") }

        assertEquals(code.count { char -> char == '(' }, code.count { char -> char == ')' })
        assertEquals(code.count { char -> char == '{' }, code.count { char -> char == '}' })
    }

    /** GLSL leaves smoothstep undefined for edge0 >= edge1, and some GPUs then return garbage. */
    @Test
    fun everySmoothstepHasRisingEdges() {
        val constantPattern = Regex("""const\s+float\s+(\w+)\s*=\s*([-0-9.]+)\s*;""")
        val constantMatches = constantPattern.findAll(source)
        val constants = constantMatches.associate { match -> match.groupValues[1] to match.groupValues[2].toFloat() }

        val callPattern = Regex("""smoothstep\(\s*([^,()]+?)\s*,\s*([^,()]+?)\s*,""")
        val calls = callPattern.findAll(source).toList()
        val writtenCalls = Regex("""smoothstep\(""").findAll(source).count()
        assertEquals("every smoothstep call is checked", writtenCalls, calls.size)

        for (call in calls) {
            val lowText = call.groupValues[1]
            val highText = call.groupValues[2]
            val low = checkNotNull(evaluate(lowText, constants)) { "unknown edge $lowText" }
            val high = checkNotNull(evaluate(highText, constants)) { "unknown edge $highText" }

            assertTrue("smoothstep($lowText, $highText, ...)", low < high)
        }
    }

    /** A literal, a constant or a product of those, else null. */
    private fun evaluate(expression: String, constants: Map<String, Float>): Float? {
        val factors = expression.split("*").map { factor -> factor.trim() }
        var product = 1f
        for (factor in factors) {
            val value = factor.toFloatOrNull() ?: constants[factor] ?: return null
            product *= value
        }
        return product
    }
}
