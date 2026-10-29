package com.danemadsen.curie.inference

import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * When the model's web searches and pages open. The model reaches for them to look up what it
 * already knows (and no prompt wording stops it), but it cannot read a page, and one opening after
 * every question would cover Curie. So a lookup opens only when the user's typed words ask for the
 * web; otherwise it is held back: the model reads that nothing opened, and a chat keeps the lookup
 * as a link to tap. Either way the model's next round may not call tools: it cannot read what the
 * lookup found, and would otherwise look the same thing up again until it ran out of rounds.
 */
object WebLookups {
    /** The status of a held-back lookup's result, which also marks its record as a link. */
    const val HELD_BACK_STATUS = "not_opened"

    private const val HELD_BACK_NOTE =
        "Web pages only open when the user asks for them, and you cannot read them. Answer from what you know."

    /**
     * Typed words that ask for the web: "search", "google", "look it up", "online", "website" or a
     * web address. English only, like the prompts.
     */
    private val WEB_REQUEST = Regex(
        "\\b(search|google|bing|look\\s+(it\\s+|that\\s+|this\\s+)?up|online|internet|the web|browse|browser|" +
            "website|web\\s?page|web\\s?site|url|link)\\b|https?://|\\b[\\w-]+\\.(com|org|net|io|gov|edu|au|uk|dev|info)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [userText], the user's typed message, asks for the web; a voice message has none. */
    fun isRequestedIn(userText: String): Boolean = WEB_REQUEST.containsMatchIn(userText)

    /** [outcome] as the model reads it when it stays closed, unless [isRequested] or it is no lookup. */
    fun applyTo(outcome: ToolOutcome, isRequested: Boolean): ToolOutcome {
        val opensNothing = outcome.deferredLaunch == null
        if (!outcome.isWebLookup || isRequested || opensNothing) {
            return outcome
        }
        val result = buildJsonObject {
            put("status", HELD_BACK_STATUS)
            put("note", HELD_BACK_NOTE)
        }
        return outcome.copy(result = result, deferredLaunch = null)
    }

    fun isHeldBack(outcome: ToolOutcome): Boolean = statusOf(outcome.result) == HELD_BACK_STATUS

    /** A stored call whose lookup was held back: the chat shows it as a link. */
    fun isHeldBack(record: ToolCallRecord): Boolean {
        val resultJson = record.resultJson ?: return false
        val result = try {
            Json.parseToJsonElement(resultJson) as? JsonObject
        } catch (error: SerializationException) {
            null
        }
        return result != null && statusOf(result) == HELD_BACK_STATUS
    }

    private fun statusOf(result: JsonObject): String? {
        val status = result["status"] as? JsonPrimitive
        return status?.contentOrNull
    }
}
