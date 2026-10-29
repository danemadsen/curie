package com.danemadsen.curie.engine.tokenizer

import com.danemadsen.curie.engine.ModelFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.InputStream

/**
 * The golden data of tools/golden/generate-text-golden.py, from the test resources.
 */
internal object Goldens {
    private const val DIRECTORY = "/golden/text/"

    fun stream(name: String): InputStream {
        val stream = Goldens::class.java.getResourceAsStream(DIRECTORY + name)
        return requireNotNull(stream) { "missing golden resource $name" }
    }

    fun text(name: String): String = stream(name).use { stream -> stream.readBytes().decodeToString() }

    fun cases(name: String): List<JsonObject> {
        val root = Json.parseToJsonElement(text(name))
        return root.jsonArray.map { case -> case as JsonObject }
    }

    fun ids(element: JsonElement?): IntArray {
        val array = requireNotNull(element) { "missing ids" }.jsonArray
        return IntArray(array.size) { index -> array[index].jsonPrimitive.int }
    }

    fun ids(array: JsonArray): IntArray = ids(array as JsonElement)
}

/**
 * The tokenizers the golden tests run on: the minimized fixture always, and the model's real
 * tokenizer.json when CURIE_MODEL_DIR (system property curie.modelDir) holds it.
 */
internal object TestTokenizers {
    val fixture: BpeTokenizer by lazy {
        Goldens.stream("tokenizer-fixture.json").use { stream -> BpeTokenizer.load(stream) }
    }

    val flagsFixture: BpeTokenizer by lazy {
        Goldens.stream("tokenizer-flags-fixture.json").use { stream -> BpeTokenizer.load(stream) }
    }

    /** The real tokenizer.json, or null when the model folder is not configured. */
    val realFile: File? by lazy {
        val modelDir = System.getProperty("curie.modelDir") ?: return@lazy null
        val file = File(modelDir, ModelFiles.TOKENIZER)
        if (file.isFile) file else null
    }

    val real: BpeTokenizer? by lazy { realFile?.let { file -> BpeTokenizer.load(file) } }

    /** Both tokenizers by name; the real one skips its tests when it is not available. */
    val sources: List<Pair<String, () -> BpeTokenizer>> = listOf(
        "fixture" to { fixture },
        "real tokenizer.json" to { requireReal() },
    )

    fun requireReal(): BpeTokenizer {
        val tokenizer = real
        assumeTrue(tokenizer != null, "set CURIE_MODEL_DIR to a folder holding ${ModelFiles.TOKENIZER}")
        return requireNotNull(tokenizer)
    }

    /** The real tokenizer.json, without loading it. */
    fun requireRealFile(): File {
        val file = realFile
        assumeTrue(file != null, "set CURIE_MODEL_DIR to a folder holding ${ModelFiles.TOKENIZER}")
        return requireNotNull(file)
    }
}

/** GPT-2's byte-level character for [byte], computed independently of [ByteLevel]. */
internal fun byteLevelChar(byte: Int): Char {
    require(byte in 0..255) { "$byte is not a byte" }
    var shifted = 0x100
    for (candidate in 0..byte) {
        val printable = candidate in 0x21..0x7E || candidate in 0xA1..0xAC || candidate in 0xAE..0xFF
        if (candidate == byte) {
            return if (printable) candidate.toChar() else shifted.toChar()
        }
        if (!printable) {
            shifted++
        }
    }
    throw IllegalStateException("unreachable")
}
