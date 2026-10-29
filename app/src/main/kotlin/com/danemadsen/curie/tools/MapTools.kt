package com.danemadsen.curie.tools

import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * navigate: turn-by-turn navigation in Google Maps, then Maps directions in the browser, then any
 * maps app showing the destination (geo: cannot ask for navigation).
 */
internal class NavigateTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "navigation"
    override val failureSummary = R.string.tool_failure_navigate

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val destination = arguments.requiredString("destination")
        val modeArgument = arguments.optionalChoice("mode", TravelMode.ARGUMENTS)
        val mode = if (modeArgument == null) TravelMode.DRIVING else TravelMode.fromArgument(modeArgument)

        val googleNavigation = AssistantIntents.googleNavigation(destination, mode)
        val directions = AssistantIntents.mapsDirections(destination, mode)
        val placeOnly = AssistantIntents.geoSearch(destination)
        val candidates = listOfNotNull(googleNavigation, directions, placeOnly)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("destination", destination)
            put("mode", mode.argument)
        }
        val failureText = services.text(failureSummary)
        val noHandlerError = "No maps app or browser on this phone can show directions."

        val showsPlaceOnly = launch.chosenIntent === placeOnly
        val report = if (showsPlaceOnly) {
            LaunchReport(
                icon = icon,
                summary = services.text(R.string.tool_summary_show_on_map, destination),
                failureSummary = failureText,
                status = "place_shown_no_navigation_app",
                details = details,
                noHandlerError = noHandlerError,
            )
        } else {
            LaunchReport(
                icon = icon,
                summary = services.text(R.string.tool_summary_navigate, destination),
                failureSummary = failureText,
                status = "navigating",
                details = details,
                noHandlerError = noHandlerError,
            )
        }
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "navigate",
            description = "Navigate to a place.",
            parameters = toolParameters {
                string("destination", required = true)
                choice("mode", TravelMode.ARGUMENTS)
            },
        )
    }
}

/** show_on_map: a place search in any maps app, or Google Maps in the browser. */
internal class ShowOnMapTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "map-pin"
    override val failureSummary = R.string.tool_failure_show_on_map

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val query = arguments.requiredString("query")

        val mapsApp = AssistantIntents.geoSearch(query)
        val mapsWebsite = AssistantIntents.mapsSearch(query)
        val candidates = listOf(mapsApp, mapsWebsite)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("query", query)
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_show_on_map, query),
            failureSummary = services.text(failureSummary),
            status = "map_opened",
            details = details,
            noHandlerError = "No maps app or browser on this phone can show places.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "show_on_map",
            description = "Show a place on a map.",
            parameters = toolParameters {
                string("query", required = true)
            },
        )
    }
}
