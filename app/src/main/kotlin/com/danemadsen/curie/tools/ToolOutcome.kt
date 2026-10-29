package com.danemadsen.curie.tools

import android.content.Intent
import kotlinx.serialization.json.JsonObject

/** What a tool call did: the JSON the model reads back and what the UI shows for it. */
data class ToolOutcome(
    /** The tool result the model reads; failures carry an "error" message it can relay. */
    val result: JsonObject,

    /** Short text for the UI, for example "Opening YouTube". */
    val summary: String,

    /** The Tabler icon name the UI draws next to [summary], for example "brand-youtube". */
    val icon: String,

    /**
     * The app-switching intent to start once Curie has finished answering (see [ToolOrigin]). It
     * already carries FLAG_ACTIVITY_NEW_TASK and an app on this device handles it.
     */
    val deferredLaunch: Intent?,

    val success: Boolean,

    /**
     * Runtime permissions whose absence stopped or limited this action. Request them from a visible
     * activity (first use) so that asking again can succeed; empty for most outcomes.
     */
    val permissionsNeeded: List<String> = emptyList(),

    /**
     * A web search or page. A small model also calls these to look up what it already knows, so
     * the caller opens one only when the user asked for the web (see WebLookups).
     */
    val isWebLookup: Boolean = false,
)
