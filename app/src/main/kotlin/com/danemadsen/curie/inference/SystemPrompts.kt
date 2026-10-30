package com.danemadsen.curie.inference

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The system prompts Curie sends. A text is read aloud with the model card's TTS prompt, the
 * format the Curie voice LoRA was trained on. A spoken answer (interleaved text and audio) needs
 * no modality line, because the engine opens it with the interleaving marker itself; it carries the
 * Curie voice only in part (see the README).
 *
 * A system prompt opens every conversation's cache, so it only changes when its inputs do: the date
 * is day-granular and the time of day comes from a tool instead.
 */
object SystemPrompts {
    const val TEXT_TO_SPEECH = "Perform TTS in American English using a female voice."

    private const val PERSONA =
        "You are Curie, a warm and capable assistant running entirely on the user's Android phone. " +
            "You can read text, look at photos and videos, listen to audio and speak."

    private const val TOOL_GUIDANCE =
        "When the user asks you to do something on the phone, such as playing music or a video, navigating " +
            "somewhere, calling or messaging someone, setting an alarm or a timer, adding a calendar event, " +
            "searching the web, opening an app or using the flashlight, call the matching tool. After a tool " +
            "runs, tell the user briefly what happened."

    private const val CHAT_STYLE = "Keep your answers clear and concise."

    private const val CALL_STYLE =
        "You are talking with the user on a voice call: answer naturally in one to three short spoken " +
            "sentences, without lists, markdown or emoji."

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    fun chat(userName: String, today: LocalDate): String {
        val lines = listOf(PERSONA, CHAT_STYLE, TOOL_GUIDANCE, context(userName, today))
        return lines.joinToString("\n")
    }

    fun call(userName: String, today: LocalDate): String {
        val lines = listOf(PERSONA, CALL_STYLE, TOOL_GUIDANCE, context(userName, today))
        return lines.joinToString("\n")
    }

    private fun context(userName: String, today: LocalDate): String {
        val date = DATE_FORMAT.format(today)
        val name = userName.trim()
        if (name.isEmpty()) {
            return "Today is $date."
        }
        return "The user's name is $name. Today is $date."
    }
}
