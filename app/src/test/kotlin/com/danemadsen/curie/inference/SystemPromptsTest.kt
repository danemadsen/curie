package com.danemadsen.curie.inference

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The prompts that decide Curie's voice and what it knows of the user. */
class SystemPromptsTest {
    private val today = LocalDate.of(2026, 10, 30)

    @Test
    fun spokenAnswersNeedNoModalityLine() {
        // The engine opens a spoken answer with the interleaving marker itself
        val prompts = listOf(SystemPrompts.chat("Dane", today), SystemPrompts.call("Dane", today))
        for (prompt in prompts) {
            assertFalse(prompt.contains("interleaved", ignoreCase = true), prompt)
        }
        assertTrue(SystemPrompts.TEXT_TO_SPEECH.startsWith("Perform TTS"))
    }

    @Test
    fun theUsersNameAndTheDateAreIncluded() {
        val prompt = SystemPrompts.call("Dane", today)

        assertTrue(prompt.contains("The user's name is Dane."), prompt)
        assertTrue(prompt.contains("Friday, 30 October 2026"), prompt)
    }
}
