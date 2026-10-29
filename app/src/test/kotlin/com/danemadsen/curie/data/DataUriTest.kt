package com.danemadsen.curie.data

import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DataUriTest {
    private fun decode(uri: String): ByteArray {
        val dataUri = DataUri.parse(uri)
        assertNotNull(dataUri, "not a data URI: $uri")
        return dataUri.openContent().use { content -> content.readBytes() }
    }

    @Test
    fun contentSurvivesTheRoundTrip() {
        val content = Random(7).nextBytes(100_001)

        val uri = DataUri.encode("image/png", content)

        assertEquals("image/png", DataUri.parse(uri)?.mimeType)
        assertContentEquals(content, decode(uri))
        assertContentEquals(ByteArray(0), decode(DataUri.encode("audio/wav", ByteArray(0))))
    }

    @Test
    fun theTypeKeepsItsParameters() {
        assertEquals("audio/wav;rate=16000", DataUri.parse("data:audio/wav;rate=16000;base64,AAAA")?.mimeType)
    }

    @Test
    fun onlyBase64DataUrisParse() {
        assertNull(DataUri.parse("https://example.com/a.png"))
        assertNull(DataUri.parse("data:image/png,plain text"))
        assertNull(DataUri.parse("data:image/png;base64"))
    }

    @Test
    fun aPayloadThatIsNotBase64FailsTheRead() {
        assertFailsWith<IOException> { decode("data:image/png;base64,***") }
        // A character beyond ASCII must not be cut down to a byte that passes for base64: 'Ł' is U+0141
        assertFailsWith<IOException> { decode("data:image/png;base64,AAAŁ") }
    }
}
