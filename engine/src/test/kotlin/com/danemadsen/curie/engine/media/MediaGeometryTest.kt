package com.danemadsen.curie.engine.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Golden cases from tools/golden/generate-geometry-golden.py (the reference processors' code). */
class MediaGeometryTest {
    private val golden: JsonObject by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/golden/geometry.json")) { "missing golden file" }
        val text = stream.bufferedReader().use { reader -> reader.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun imageResizeTargetsMatchTheImageProcessor() {
        for (case in golden.cases("images")) {
            val width = case.int("width")
            val height = case.int("height")
            val maxTokens = case.int("maxTokens")

            val target = MediaGeometry.imageResizeTarget(width, height, maxTokens)

            val expected = MediaGeometry.Size(case.int("outWidth"), case.int("outHeight"))
            assertEquals(expected, target, "image ${width}x$height, $maxTokens tokens")
        }
    }

    @Test
    fun videoSamplingMatchesTheVideoProcessor() {
        for (case in golden.cases("videos")) {
            val width = case.int("width")
            val height = case.int("height")
            val frameCount = case.int("frameCount")
            val fps = case.double("fps")
            val label = "video ${width}x$height, $frameCount frames at $fps fps"

            val indices = MediaGeometry.videoFrameIndices(frameCount, fps, case.int("maxFrames"))
            assertContentEquals(case.ints("indices"), indices, "$label: frame indices")

            val timestamps = MediaGeometry.videoGroupTimestamps(indices, fps)
            assertContentEquals(case.doubles("timestamps"), timestamps, "$label: timestamps")

            val texts = timestamps.map { seconds -> MediaGeometry.timestampText(seconds) }
            val expectedTexts = case.getValue("timestampTexts").jsonArray.map { text -> text.jsonPrimitive.content }
            assertEquals(expectedTexts, texts, "$label: timestamp texts")

            val bounds = MediaGeometry.soundtrackBounds(indices, fps, case.int("soundtrackTokens"))
            assertContentEquals(case.ints("soundtrackBounds"), bounds, "$label: soundtrack bounds")

            if ("outWidth" in case) {
                val target = MediaGeometry.videoResizeTarget(width, height, indices.size, case.int("maxTokens"))
                val expected = MediaGeometry.Size(case.int("outWidth"), case.int("outHeight"))
                assertEquals(expected, target, "$label: resize target")
            }
        }
    }

    @Test
    fun timestampTextsRoundTheBinaryValueHalfToEven() {
        for (case in golden.cases("timestampTexts")) {
            val seconds = case.double("seconds")

            val text = MediaGeometry.timestampText(seconds)

            assertEquals(case.getValue("text").jsonPrimitive.content, text, "timestamp $seconds")
        }
    }

    @Test
    fun audioTokenCountsMatchTheFeatureExtractor() {
        for (case in golden.cases("audio")) {
            val samples = case.int("samples")

            assertEquals(case.int("melFrames"), MediaGeometry.melFrameCount(samples), "$samples samples: mel frames")
            assertEquals(case.int("tokens"), MediaGeometry.audioTokenCount(samples), "$samples samples: tokens")
        }
    }

    private fun JsonObject.cases(name: String): List<JsonObject> =
        getValue(name).jsonArray.map { element -> element.jsonObject }

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double

    private fun JsonObject.ints(name: String): IntArray {
        val values = getValue(name) as JsonArray
        return IntArray(values.size) { index -> values[index].jsonPrimitive.int }
    }

    private fun JsonObject.doubles(name: String): DoubleArray {
        val values = getValue(name) as JsonArray
        return DoubleArray(values.size) { index -> values[index].jsonPrimitive.double }
    }
}
