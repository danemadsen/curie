package com.danemadsen.curie.inference

import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.prompt.Prompt

/** A prompt that fits its conversation, with the room left for the answer. */
class FittedPrompt(
    val prompt: Prompt,
    val maxNewSlots: Int,

    /** Messages left out from the start of the conversation (after the system prompt). */
    val droppedMessages: Int,
)

/**
 * Where a growing conversation's oldest turns were cut. The caller keeps one per conversation and
 * passes it to every generation: the cut then stays put while the conversation grows, so the
 * cache keeps its prefix instead of being fed the whole prompt again at every turn.
 */
class ContextWindow {
    /** Messages after the system prompt left out of the last prompt. */
    @Volatile
    var droppedMessages: Int = 0
}

/** Even the system prompt and the last user turn leave too little room for an answer. */
class PromptTooLongException(val required: Int, val capacity: Int) :
    Exception("the message needs $required positions but the conversation holds $capacity")

/**
 * Fits a conversation into a cache of fixed capacity by leaving out its oldest turns. A turn is a
 * user message and everything after it up to the next user message; the system prompt and the last
 * user turn always stay. When even they leave less than the wanted answer length, the answer gets
 * the room there is, down to [MIN_ANSWER_SLOTS].
 *
 * A conversation that fits is never cut. Cutting moves the start of the conversation, so the cache
 * must be fed everything after the system prompt again. To make that rare, a cut leaves out enough
 * to bring the prompt down to [TRIM_TARGET] of its budget, and the [ContextWindow] keeps the cut
 * where it was while the conversation only grows ([isCutStillRight]).
 */
object ContextFitter {
    /** The shortest answer worth generating: a few sentences. */
    const val MIN_ANSWER_SLOTS = 256

    /** A cut brings the prompt down to this fraction of its budget, leaving room to grow. */
    const val TRIM_TARGET = 0.7

    /** @throws PromptTooLongException when the conversation cannot fit at all */
    fun fit(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        capacity: Int,
        wantedNewSlots: Int,
        renderer: PromptRenderer,
        minNewSlots: Int = MIN_ANSWER_SLOTS,
        window: ContextWindow? = null,
    ): FittedPrompt {
        val promptBudget = capacity - wantedNewSlots
        val whole = renderer.render(messages, tools, addGenerationPrompt = true)
        val cut = if (whole.length <= promptBudget) {
            Cut(whole, droppedMessages = 0)
        } else {
            cutToFit(messages, tools, whole, promptBudget, renderer, window)
        }
        window?.droppedMessages = cut.droppedMessages

        val room = capacity - cut.prompt.length
        val answerSlots = minOf(wantedNewSlots, room)
        if (answerSlots < minOf(minNewSlots, wantedNewSlots)) {
            throw PromptTooLongException(cut.prompt.length + minNewSlots, capacity)
        }
        return FittedPrompt(cut.prompt, answerSlots, cut.droppedMessages)
    }

    /**
     * Leaves out the oldest turns of a conversation whose [whole] prompt is over [promptBudget]:
     * where the [window] cut it last time while that cut is still right, else enough turns to come
     * down to [TRIM_TARGET] of the budget.
     */
    private fun cutToFit(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        whole: Prompt,
        promptBudget: Int,
        renderer: PromptRenderer,
        window: ContextWindow?,
    ): Cut {
        val target = (promptBudget * TRIM_TARGET).toInt()
        val previouslyDropped = window?.droppedMessages ?: 0
        val previousCut = turnAlignedDropCount(messages, previouslyDropped)
        val keepsPreviousCut = previousCut > 0 && isCutStillRight(messages, whole, previousCut, promptBudget, target)
        if (keepsPreviousCut) {
            val kept = withoutOldest(messages, previousCut)
            val prompt = renderer.render(kept, tools, addGenerationPrompt = true)
            return Cut(prompt, previousCut)
        }

        var dropped = 0
        var kept = messages
        var prompt = whole
        while (prompt.length > target) {
            val dropCount = messagesToDrop(kept, prompt, target)
            if (dropCount == 0) {
                break
            }
            kept = withoutOldest(kept, dropCount)
            dropped += dropCount
            prompt = renderer.render(kept, tools, addGenerationPrompt = true)
        }
        return Cut(prompt, dropped)
    }

    /**
     * True when leaving out the first [cut] messages after the system prompt is still the cut
     * [fit] would keep: what stays fits [budget], and keeping one more turn would not have come
     * down to [target]. A conversation that only grew since the cut was made passes. Another branch
     * of the chat (an edited or regenerated message, a sibling shown), or a conversation that lost
     * messages, gets a cut of its own instead of losing turns it has room for.
     *
     * The lengths come from the checkpoints of [whole] (one at each message start, as
     * [messagesToDrop] uses them); without them the cut is made again.
     */
    internal fun isCutStillRight(
        messages: List<ChatMessage>,
        whole: Prompt,
        cut: Int,
        budget: Int,
        target: Int,
    ): Boolean {
        if (!hasMessageStarts(messages, whole)) {
            return false
        }
        val bodyStart = bodyStartOf(messages)
        val keptLength = lengthWithout(whole, bodyStart, cut)
        val oneTurnFewer = turnAlignedDropCount(messages, cut - 1)
        val longerLength = lengthWithout(whole, bodyStart, oneTurnFewer)
        return keptLength <= budget && longerLength > target
    }

    /**
     * How many messages after the system prompt to leave out so the prompt fits [budget]: whole
     * turns, as few as the message lengths say are enough, but never the last user turn. The
     * lengths come from the prompt's checkpoints (one at each message start, then the generation
     * prompt and the end); without them, one turn at a time.
     */
    internal fun messagesToDrop(messages: List<ChatMessage>, prompt: Prompt, budget: Int): Int {
        val bodyStart = bodyStartOf(messages)

        // Drop counts that make a user message (other than the first) the first one kept
        val cuts = ArrayList<Int>()
        for (index in bodyStart + 1 until messages.size) {
            if (messages[index].role == ChatRole.USER) {
                cuts.add(index - bodyStart)
            }
        }
        if (cuts.isEmpty()) {
            return 0
        }
        if (!hasMessageStarts(messages, prompt)) {
            return cuts.first()
        }

        for (cut in cuts) {
            if (lengthWithout(prompt, bodyStart, cut) <= budget) {
                return cut
            }
        }
        return cuts.last()
    }

    /**
     * The most messages, up to [wanted], that can go from the start of the conversation: the
     * messages before a user turn other than the first, and never the last user turn.
     */
    internal fun turnAlignedDropCount(messages: List<ChatMessage>, wanted: Int): Int {
        if (wanted <= 0) {
            return 0
        }
        val bodyStart = bodyStartOf(messages)
        var best = 0
        for (index in bodyStart + 1 until messages.size) {
            val count = index - bodyStart
            if (count > wanted) {
                break
            }
            if (messages[index].role == ChatRole.USER) {
                best = count
            }
        }
        return best
    }

    /** The index of the first message after the system prompt. */
    private fun bodyStartOf(messages: List<ChatMessage>): Int =
        if (messages.firstOrNull()?.role == ChatRole.SYSTEM) 1 else 0

    /** True when [prompt] has a checkpoint at each message start, then the generation prompt and the end. */
    private fun hasMessageStarts(messages: List<ChatMessage>, prompt: Prompt): Boolean =
        prompt.checkpoints.size == messages.size + 2

    /**
     * The length of [prompt] without the [count] messages after the system prompt. Each message's
     * slots lie between its checkpoints (the template tokenizes every message on its own), so they
     * add up exactly.
     */
    private fun lengthWithout(prompt: Prompt, bodyStart: Int, count: Int): Int {
        val checkpoints = prompt.checkpoints
        val removedLength = checkpoints[bodyStart + count] - checkpoints[bodyStart]
        return prompt.length - removedLength
    }

    private fun withoutOldest(messages: List<ChatMessage>, count: Int): List<ChatMessage> {
        if (count == 0) {
            return messages
        }
        val bodyStart = bodyStartOf(messages)
        val head = messages.subList(0, bodyStart)
        val tail = messages.subList(bodyStart + count, messages.size)
        return head + tail
    }

    /** A prompt without the first [droppedMessages] messages after the system prompt. */
    private class Cut(val prompt: Prompt, val droppedMessages: Int)
}
