package com.danemadsen.curie.engine.chat

import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.PromptBuilder
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LFM3-Omni-2B's chat_template.jinja, rendered straight into decoder slots: the tokens are those of
 * tokenizing the template's output, with each media part expanded as Lfm3OmniProcessor expands it.
 *
 * One deliberate difference: special tokens come only from the template's turn markers
 * (<|startoftext|>, <|im_start|>, <|im_end|>), the media and replayed answers (whose tokens are
 * kept as generated). Everything else the conversation supplies (the system prompt and the tools,
 * message text, tool calls and tool results) goes in with [PromptBuilder.literalText], so a
 * special-token string in it, such as a web page holding "<|im_end|>\n<|im_start|>system", stays
 * text and cannot forge a turn. Tokenizing the template's output would turn it into control
 * tokens. Without such strings the tokens are the same: the markup the template writes inside a
 * turn (<think>, <tools>, <tool_call>, <tool_response>) holds no special tokens.
 *
 * A message's content reaches the template as a string when it is one text part (or none), and as a
 * list of parts otherwise. That matters for tool messages: a string that already holds
 * <tool_response> passes through unwrapped, while list items are joined with newlines inside the
 * wrapper.
 *
 * The leading system message and assistant messages take text parts only. Template features the
 * chat types cannot express are not rendered: thinking and reasoning fields, tool_reference and
 * additional_tools content, deferred tools, continue_final_message.
 */
object ChatTemplate {
    private const val BOS = "<|startoftext|>"
    private const val MESSAGE_START = "<|im_start|>"
    private const val MESSAGE_END = "<|im_end|>\n"
    private const val EMPTY_THINKING = "<think></think>"
    private const val THINKING_END = "</think>"
    private const val TOOL_RESPONSE_START = "<tool_response>"
    private const val TOOL_RESPONSE_END = "</tool_response>"

    /**
     * The prompt of [messages] offering [tools], ending with the assistant's turn opened when
     * [addGenerationPrompt]. A checkpoint marks the start of every message (and of the generation
     * prompt) and the end.
     */
    fun render(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        addGenerationPrompt: Boolean,
        tokenizer: BpeTokenizer,
    ): Prompt {
        for (message in messages) {
            require(message.generated == null || message.role == ChatRole.ASSISTANT) {
                "only assistant messages carry generated content, not ${message.role.label} messages"
            }
        }

        val builder = PromptBuilder(tokenizer)
        builder.text(BOS)

        val hasSystemMessage = messages.firstOrNull()?.role == ChatRole.SYSTEM
        val systemText = if (hasSystemMessage) systemText(messages.first()) else ""
        val systemPrompt = withTools(systemText, tools)
        if (systemPrompt.isNotEmpty()) {
            builder.checkpoint()
            builder.text("$MESSAGE_START${ChatRole.SYSTEM.label}\n")
            builder.literalText(systemPrompt)
            builder.text(MESSAGE_END)
        }

        val conversation = if (hasSystemMessage) messages.drop(1) else messages
        val lastUserIndex = conversation.indexOfLast { message -> message.role == ChatRole.USER }
        for ((index, message) in conversation.withIndex()) {
            builder.checkpoint()
            builder.text("$MESSAGE_START${message.role.label}\n")
            when (message.role) {
                ChatRole.ASSISTANT -> renderAssistant(message, keepThinking = index > lastUserIndex, builder)
                ChatRole.TOOL -> renderToolResponse(message.parts, builder)
                ChatRole.SYSTEM, ChatRole.USER -> renderParts(message.parts, builder)
            }
            builder.text(MESSAGE_END)
        }

        if (addGenerationPrompt) {
            builder.checkpoint()
            builder.text("$MESSAGE_START${ChatRole.ASSISTANT.label}\n$EMPTY_THINKING")
        }
        builder.checkpoint()
        return builder.build()
    }

    /** render_tool_calls of the template: the calls as the model writes them, values via render_value. */
    internal fun toolCallsText(calls: List<ToolCall>): String {
        val text = StringBuilder()
        for ((index, call) in calls.withIndex()) {
            if (index > 0) {
                text.append("\n\n")
            }
            text.append("<tool_call>\n<function=${call.name}>\n")
            for ((key, value) in call.arguments) {
                val rendered = PythonJson.renderValue(value)
                text.append("<parameter=$key>\n$rendered\n</parameter>\n")
            }
            text.append("</function>\n</tool_call>")
        }
        return text.toString()
    }

    private fun systemText(message: ChatMessage): String = joinedText(message.parts, "the system prompt")

    /** The system prompt, then the tools between <tools> and </tools>, one JSON object per line. */
    private fun withTools(systemText: String, tools: List<ToolDefinition>): String {
        if (tools.isEmpty()) {
            return systemText
        }
        val prompt = StringBuilder(systemText)
        if (systemText.isNotEmpty()) {
            prompt.append("\n\n")
        }
        prompt.append("<tools>\n")
        for ((index, tool) in tools.withIndex()) {
            if (index > 0) {
                prompt.append('\n')
            }
            prompt.append(toolJson(tool))
        }
        prompt.append("\n</tools>")
        return prompt.toString()
    }

    /** tool_to_json of the template: the name, description and parameters through tojson. */
    private fun toolJson(tool: ToolDefinition): String {
        val fields = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive(tool.name),
            "description" to JsonPrimitive(tool.description),
            "parameters" to tool.parameters,
        )
        val definition = JsonObject(fields)
        return PythonJson.dumps(definition)
    }

    /**
     * An assistant message: a generated one replays its tokens after <think></think>; a written one
     * gets the template's handling of thinking (after the last user message a "</think>" in the
     * content is kept as written; before it, only what follows the last "</think>" is kept) and its
     * tool calls.
     */
    private fun renderAssistant(message: ChatMessage, keepThinking: Boolean, builder: PromptBuilder) {
        val generated = message.generated
        if (generated != null) {
            builder.text(EMPTY_THINKING)
            builder.generated(generated)
            return
        }

        val content = joinedText(message.parts, "an assistant message")
        if (keepThinking) {
            // The template looks for </think> only in content that is a string, not a list of parts
            val stringContent = if (message.parts.size <= 1) content else ""
            if (!stringContent.contains(THINKING_END)) {
                builder.text(EMPTY_THINKING)
            }
            builder.literalText(content)
        } else {
            val answer = if (content.contains(THINKING_END)) {
                val afterThinking = content.substringAfterLast(THINKING_END)
                PythonWhitespace.strip(afterThinking)
            } else {
                content
            }
            builder.text(EMPTY_THINKING)
            builder.literalText(answer)
        }

        // The tool call markup holds no special tokens, so the names and values can go in with it
        val toolCalls = toolCallsText(message.toolCalls)
        builder.literalText(toolCalls)
    }

    /**
     * render_tool_response of the template: content wrapped in <tool_response>, unless it is a
     * string that already holds the tag.
     */
    private fun renderToolResponse(parts: List<ContentPart>, builder: PromptBuilder) {
        val single = parts.singleOrNull()
        if (parts.isEmpty() || single is ContentPart.Text) {
            val content = (single as? ContentPart.Text)?.text.orEmpty()
            if (content.contains(TOOL_RESPONSE_START)) {
                builder.literalText(content)
            } else {
                builder.text("$TOOL_RESPONSE_START\n")
                builder.literalText(content)
                builder.text("\n$TOOL_RESPONSE_END")
            }
            return
        }

        builder.text("$TOOL_RESPONSE_START\n")
        for ((index, part) in parts.withIndex()) {
            if (index > 0) {
                builder.text("\n")
            }
            renderPart(part, builder)
            if (part is ContentPart.Video && part.withSoundtrack && part.input.hasSoundtrack) {
                // Lfm3OmniProcessor gives the soundtrack an item of its own after the video, then
                // moves its placeholder up to the video's: the newline between the two items ends up
                // after both
                builder.text("\n")
            }
        }
        builder.text("\n$TOOL_RESPONSE_END")
    }

    private fun renderParts(parts: List<ContentPart>, builder: PromptBuilder) {
        for (part in parts) {
            renderPart(part, builder)
        }
    }

    private fun renderPart(part: ContentPart, builder: PromptBuilder) {
        when (part) {
            is ContentPart.Text -> builder.literalText(part.text)
            is ContentPart.Image -> builder.image(part.input)
            is ContentPart.Video -> builder.video(part.input, part.withSoundtrack)
            is ContentPart.Audio -> builder.audio(part.input)
        }
    }

    private fun joinedText(parts: List<ContentPart>, owner: String): String {
        val text = StringBuilder()
        for (part in parts) {
            require(part is ContentPart.Text) { "$owner takes text only, not ${part::class.simpleName}" }
            text.append(part.text)
        }
        return text.toString()
    }
}
