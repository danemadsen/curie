package com.danemadsen.curie.tools

import android.content.Intent
import androidx.core.net.toUri
import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** web_search: the search app's web search, or a Google results page in the browser. */
internal class WebSearchTool(private val services: ToolServices) : AssistantTool, WebLinkTool {
    override val definition = DEFINITION
    override val icon = "search"
    override val failureSummary = R.string.tool_failure_web_search

    override fun linkCandidates(arguments: ToolArguments): List<Intent> {
        val query = arguments.requiredString("query")
        val searchApp = AssistantIntents.webSearch(query)
        val searchPage = AssistantIntents.webSearchPage(query)
        return listOf(searchApp, searchPage)
    }

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val query = arguments.requiredString("query")
        val candidates = linkCandidates(arguments)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("query", query)
            put("note", "The results show on the user's screen; you cannot read them.")
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_web_search, query),
            failureSummary = services.text(failureSummary),
            status = "search_opened",
            details = details,
            noHandlerError = "No browser or search app on this phone can search the web.",
        )
        val outcome = launchOutcome(launch, report)
        return outcome.copy(isWebLookup = true)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "web_search",
            description = "Show the user web results when they ask to search.",
            parameters = toolParameters {
                string("query", required = true)
            },
        )
    }
}

/** open_url: a web page in the browser (or the app verified for its domain). Only http and https. */
internal class OpenUrlTool(private val services: ToolServices) : AssistantTool, WebLinkTool {
    override val definition = DEFINITION
    override val icon = "world"
    override val failureSummary = R.string.tool_failure_open_url

    override fun linkCandidates(arguments: ToolArguments): List<Intent> {
        val text = arguments.requiredString("url")
        val address = WebAddresses.parse(text) ?: throw InvalidToolArgumentException("\"$text\" is not a web address.")
        val pageUri = address.url.toUri()
        val pageIntent = AssistantIntents.browsableView(pageUri)
        return listOf(pageIntent)
    }

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val text = arguments.requiredString("url")
        val address = WebAddresses.parse(text)
        if (address == null) {
            return toolFailure(
                icon = icon,
                summary = services.text(failureSummary),
                error = "\"$text\" is not a web address. Only http and https pages can be opened.",
            )
        }

        val pageUri = address.url.toUri()
        val pageIntent = AssistantIntents.browsableView(pageUri)
        val candidates = listOf(pageIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("url", address.url)
            put("note", "The page shows on the user's screen; you cannot read it.")
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_open_url, address.host),
            failureSummary = services.text(failureSummary),
            status = "page_opened",
            details = details,
            noHandlerError = "No browser on this phone can open web pages.",
        )
        val outcome = launchOutcome(launch, report)
        return outcome.copy(isWebLookup = true)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "open_url",
            description = "Open a web page for the user.",
            parameters = toolParameters {
                string("url", required = true)
            },
        )
    }
}

internal data class WebAddress(val url: String, val host: String)

/**
 * Accepts http and https addresses only: a model can be talked into opening intent:, file: or
 * javascript: links, and those must never reach startActivity.
 */
internal object WebAddresses {
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * A scheme at the start: "https:", "mailto:", "javascript:". In "example.com:8080" the colon starts
     * a port, and a "://" further on ("example.com/login?next=https://...") is part of the address.
     */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9])")

    /** "example.com/page" becomes "https://example.com/page"; anything that is not a web address is null. */
    fun parse(text: String): WebAddress? {
        val trimmed = text.trim()
        val hasScheme = SCHEME_PREFIX.containsMatchIn(trimmed)
        val candidate = if (hasScheme) trimmed else "https://$trimmed"

        val uri = try {
            URI(candidate)
        } catch (exception: URISyntaxException) {
            return null
        }

        val writtenScheme = uri.scheme ?: return null
        val scheme = writtenScheme.lowercase(Locale.ROOT)
        if (scheme !in WEB_SCHEMES) {
            return null
        }

        // A host without a dot ("localhost") is not a page on the web, and "user@host" addresses
        // ("https://bank.com@evil.example") only serve to disguise where a link goes.
        val host = uri.host ?: return null
        if (!host.contains('.') || uri.rawUserInfo != null) {
            return null
        }

        // Android matches intent schemes case-sensitively: no browser handles "HTTPS://".
        val afterScheme = candidate.substring(writtenScheme.length)
        val url = scheme + afterScheme
        return WebAddress(url, host)
    }
}
