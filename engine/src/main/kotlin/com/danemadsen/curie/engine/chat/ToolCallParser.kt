package com.danemadsen.curie.engine.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool calls in the model's output, read as the model card's parse_tool_calls does:
 *
 *     <tool_call>
 *     <function=NAME>
 *     <parameter=KEY>
 *     VALUE
 *     </parameter>
 *     </function>
 *     </tool_call>
 *
 * A value that json.loads accepts is parsed ([PythonJson.loads]); any other value is a string. The
 * regexes are the model card's, with Python's \s spelled out.
 */
object ToolCallParser {
    private const val CALL_START = "<tool_call>"

    /** Python's \s* */
    private const val SPACES = "[${PythonWhitespace.CLASS}]*"

    private val CALL_PATTERN = Regex(
        "<tool_call>$SPACES<function=([^>\\n]+)>(.*?)</function>$SPACES</tool_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAMETER_PATTERN = Regex(
        "<parameter=([^>\\n]+)>\\n?(.*?)\\n?</parameter>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val BLOCK_PATTERN = Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** The complete tool calls in [text], in order. A repeated parameter keeps its last value. */
    fun parse(text: String): List<ToolCall> {
        val calls = ArrayList<ToolCall>()
        for (call in CALL_PATTERN.findAll(text)) {
            val name = call.groupValues[1]
            val body = call.groupValues[2]
            val arguments = LinkedHashMap<String, JsonElement>()
            for (parameter in PARAMETER_PATTERN.findAll(body)) {
                val key = parameter.groupValues[1]
                val value = parameter.groupValues[2]
                arguments[key] = parseValue(value)
            }
            val toolCall = ToolCall(name, JsonObject(arguments))
            calls.add(toolCall)
        }
        return calls
    }

    /**
     * [text] without tool call markup, for display: every <tool_call>...</tool_call> block goes, as
     * does an unfinished <tool_call> at the end (a call still streaming, or cut off), and the rest is
     * trimmed.
     */
    fun stripToolCalls(text: String): String {
        val withoutBlocks = BLOCK_PATTERN.replace(text, "")
        val unfinishedCall = withoutBlocks.indexOf(CALL_START)
        val visible = if (unfinishedCall >= 0) withoutBlocks.substring(0, unfinishedCall) else withoutBlocks
        return visible.trim()
    }

    private fun parseValue(value: String): JsonElement =
        try {
            PythonJson.loads(value)
        } catch (error: PythonJson.DecodeException) {
            JsonPrimitive(value)
        }
}
