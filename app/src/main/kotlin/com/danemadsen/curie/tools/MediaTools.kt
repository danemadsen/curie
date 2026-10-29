package com.danemadsen.curie.tools

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** play_music: MEDIA_PLAY_FROM_SEARCH in the user's music app, or a YouTube search when there is none. */
internal class PlayMusicTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "music"
    override val failureSummary = R.string.tool_failure_play_music

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val query = arguments.requiredString("query")
        val artist = arguments.optionalString("artist")
        val album = arguments.optionalString("album")

        val playIntent = AssistantIntents.playMusic(query, artist, album)
        val searchWords = listOfNotNull(query, artist)
        val searchText = searchWords.joinToString(" ")
        val youTubeSearch = AssistantIntents.youTubeWebSearch(searchText)
        val candidates = listOf(playIntent, youTubeSearch)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("query", query)
            putIfPresent("artist", artist)
            putIfPresent("album", album)
        }
        val failureText = services.text(failureSummary)

        val usedYouTube = launch.chosenIntent === youTubeSearch
        val report = if (usedYouTube) {
            LaunchReport(
                icon = icon,
                summary = services.text(R.string.tool_summary_youtube_search, query),
                failureSummary = failureText,
                status = "no_music_app_searching_youtube",
                details = details,
                noHandlerError = NO_HANDLER,
            )
        } else {
            LaunchReport(
                icon = icon,
                summary = services.text(R.string.tool_summary_play_music, query),
                failureSummary = failureText,
                status = "playing",
                details = details,
                noHandlerError = NO_HANDLER,
            )
        }
        return launchOutcome(launch, report)
    }

    companion object {
        private const val NO_HANDLER = "No app on this phone can play music or open YouTube."

        val DEFINITION = ToolDefinition(
            name = "play_music",
            description = "Play music.",
            parameters = toolParameters {
                string("query", "Song, artist or album", required = true)
                string("artist")
                string("album")
            },
        )
    }
}

/** play_video: a YouTube search, in the YouTube app when it is installed. */
internal class PlayVideoTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "brand-youtube"
    override val failureSummary = R.string.tool_failure_play_video

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val query = arguments.requiredString("query")

        val appSearch = AssistantIntents.youTubeAppSearch(query)
        val webSearch = AssistantIntents.youTubeWebSearch(query)
        val candidates = listOf(appSearch, webSearch)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            put("query", query)
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_youtube_search, query),
            failureSummary = services.text(failureSummary),
            status = "youtube_search_opened",
            details = details,
            noHandlerError = "No app on this phone can open YouTube.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "play_video",
            description = "Play a video on YouTube.",
            parameters = toolParameters {
                string("query", required = true)
            },
        )
    }
}

/** media_control: a media key press for whichever app is playing (or played last). */
internal class MediaControlTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "player-play"
    override val failureSummary = R.string.tool_failure_media

    private enum class MediaAction(val argument: String, val keyCode: Int, @StringRes val summary: Int) {
        PLAY("play", KeyEvent.KEYCODE_MEDIA_PLAY, R.string.tool_summary_media_play),
        PAUSE("pause", KeyEvent.KEYCODE_MEDIA_PAUSE, R.string.tool_summary_media_pause),
        NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT, R.string.tool_summary_media_next),
        PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.tool_summary_media_previous),
    }

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val argument = arguments.requiredChoice("action", ACTIONS)
        val action = MediaAction.entries.first { entry -> entry.argument == argument }

        services.device.pressMediaKey(action.keyCode)

        val result = buildJsonObject {
            put("status", "sent")
            put("action", action.argument)
        }
        val summary = services.text(action.summary)
        return toolSuccess(icon, summary, result)
    }

    companion object {
        private val ACTIONS = MediaAction.entries.map { action -> action.argument }

        val DEFINITION = ToolDefinition(
            name = "media_control",
            description = "Control media playback.",
            parameters = toolParameters {
                choice("action", ACTIONS, required = true)
            },
        )
    }
}

/** set_volume: the media volume, which is what people mean by "turn it up". */
internal class SetVolumeTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "volume"
    override val failureSummary = R.string.tool_failure_set_volume

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val percent = arguments.requiredInt("percent", 0..100)

        val actualPercent = services.device.setMediaVolume(percent)
        if (actualPercent == null) {
            return toolFailure(
                icon = icon,
                summary = services.text(failureSummary),
                error = "The phone did not let Curie change the volume.",
            )
        }

        val result = buildJsonObject {
            put("status", "volume_set")
            put("percent", actualPercent)
        }
        val summary = services.text(R.string.tool_summary_set_volume, actualPercent)
        return toolSuccess(icon, summary, result)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "set_volume",
            description = "Set the media volume.",
            parameters = toolParameters {
                integer("percent", required = true)
            },
        )
    }
}
