package com.danemadsen.curie.inference

import android.content.Intent
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.chat.PythonJson
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which web lookups open: only those the user's typed words ask for. */
class WebLookupsTest {
    private val page = Intent(Intent.ACTION_VIEW)

    private fun lookup(): ToolOutcome {
        val result = JsonObject(mapOf("status" to JsonPrimitive("search_opened")))
        return ToolOutcome(
            result = result,
            summary = "Searching the web",
            icon = "search",
            deferredLaunch = page,
            success = true,
            isWebLookup = true,
        )
    }

    @Test
    fun wordsThatAskForTheWebAreRecognised() {
        val requests = listOf(
            "Search the web for the best pizza in Brisbane.",
            "google the opening hours of the Louvre",
            "Can you look it up?",
            "Look up the weather in Paris online.",
            "Open wikipedia.org",
            "Show me https://example.com/news",
            "Find a recipe on the internet",
        )
        for (request in requests) {
            assertTrue(WebLookups.isRequestedIn(request), request)
        }
    }

    @Test
    fun questionsAreNotRequestsForTheWeb() {
        val questions = listOf(
            "What is the capital of France?",
            "Who wrote Pride and Prejudice?",
            "How far away is the moon?",
            "Researching the history of Rome, what happened in 44 BC?",
            "",
        )
        for (question in questions) {
            assertFalse(WebLookups.isRequestedIn(question), question)
        }
    }

    @Test
    fun anUnrequestedLookupIsHeldBack() {
        val heldBack = WebLookups.applyTo(lookup(), isRequested = false)

        assertNull(heldBack.deferredLaunch)
        assertTrue(WebLookups.isHeldBack(heldBack))
        assertEquals("not_opened", (heldBack.result["status"] as JsonPrimitive).content)
        assertTrue(heldBack.success)
    }

    @Test
    fun aRequestedLookupAndOtherActionsAreKept() {
        val requested = lookup()
        assertSame(requested, WebLookups.applyTo(requested, isRequested = true))

        val music = lookup().copy(isWebLookup = false)
        assertSame(music, WebLookups.applyTo(music, isRequested = false))

        // A lookup that opens nothing (no browser) keeps its error for the model
        val failed = lookup().copy(deferredLaunch = null, success = false)
        assertSame(failed, WebLookups.applyTo(failed, isRequested = false))
    }

    @Test
    fun aStoredHeldBackLookupIsALink() {
        val heldBack = WebLookups.applyTo(lookup(), isRequested = false)
        val record = ToolCallRecord(
            name = "web_search",
            argumentsJson = """{"query": "capital of France"}""",
            resultJson = PythonJson.dumps(heldBack.result),
            summary = heldBack.summary,
        )

        assertTrue(WebLookups.isHeldBack(record))
        assertFalse(WebLookups.isHeldBack(record.copy(resultJson = """{"status": "search_opened"}""")))
        assertFalse(WebLookups.isHeldBack(record.copy(resultJson = null)))
        assertFalse(WebLookups.isHeldBack(record.copy(resultJson = "not json")))
    }
}
