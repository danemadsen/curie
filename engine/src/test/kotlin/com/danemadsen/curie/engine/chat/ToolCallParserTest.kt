package com.danemadsen.curie.engine.chat

import com.danemadsen.curie.engine.tokenizer.Goldens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/** ToolCallParser against the model card's parse_tool_calls (goldens), and the markup stripping. */
class ToolCallParserTest {
    @TestFactory
    fun parsesLikeTheModelCard(): List<DynamicTest> = Goldens.cases("tool-call-cases.json").mapIndexed { index, case ->
        val text = case.getValue("text").jsonPrimitive.content
        val expected = case.getValue("calls").jsonPrimitive.content
        DynamicTest.dynamicTest("case $index") {
            val calls = ToolCallParser.parse(text)
            val asPython = calls.map { call -> callJson(call) }
            val callArray = JsonArray(asPython)
            assertEquals(expected, PythonJson.dumps(callArray), "calls in \"$text\"")
        }
    }

    @Test
    fun readsTheModelCardExample() {
        val text = "<tool_call>\n<function=set_volume>\n<parameter=volume>\n30\n</parameter>\n</function>\n</tool_call>"
        val arguments = mapOf("volume" to JsonPrimitive(30))
        val expected = ToolCall("set_volume", JsonObject(arguments))
        assertEquals(listOf(expected), ToolCallParser.parse(text))
    }

    @Test
    fun stripsCompleteAndUnfinishedCalls() {
        val call = "<tool_call>\n<function=f>\n<parameter=a>\n1\n</parameter>\n</function>\n</tool_call>"
        assertEquals("Checking.", ToolCallParser.stripToolCalls("Checking.\n$call"))
        assertEquals("Before\n\nafter", ToolCallParser.stripToolCalls("Before\n$call\nafter"))
        assertEquals("Let me look", ToolCallParser.stripToolCalls("Let me look <tool_call>\n<function=sea"))
        assertEquals("", ToolCallParser.stripToolCalls("$call\n\n$call"))
        assertEquals("No calls here.", ToolCallParser.stripToolCalls("  No calls here.\n"))
    }

    @Test
    fun parsesWhatTheTemplateRenders() {
        val tags = listOf(JsonPrimitive("a"), JsonPrimitive(2))
        val arguments = linkedMapOf<String, JsonElement>(
            "city" to JsonPrimitive("Zürich"),
            "days" to JsonPrimitive(3),
            "ratio" to JsonPrimitive(0.25),
            "metric" to JsonPrimitive(true),
            "tags" to JsonArray(tags),
            "note" to JsonPrimitive("two\nlines"),
        )
        val weather = ToolCall("get_weather", JsonObject(arguments))
        val noArguments = JsonObject(emptyMap())
        val ping = ToolCall("ping", noArguments)
        val calls = listOf(weather, ping)
        val rendered = ChatTemplate.toolCallsText(calls)

        assertEquals(calls, ToolCallParser.parse(rendered))
        assertEquals("", ToolCallParser.stripToolCalls(rendered))
    }

    private fun callJson(call: ToolCall): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(call.name), "arguments" to call.arguments)
        return JsonObject(fields)
    }
}
