package com.danemadsen.curie.tools

import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolDefinitionsTest {
    private val definitions = AssistantTools.DEFINITIONS

    @Test
    fun definesTheAssistantActions() {
        val names = definitions.map { definition -> definition.name }

        val expected = listOf(
            "play_music", "play_video", "navigate", "show_on_map", "call_contact", "send_text_message",
            "send_email", "set_alarm", "set_timer", "create_calendar_event", "web_search", "open_app",
            "open_url", "set_flashlight", "set_volume", "media_control", "open_settings", "get_device_status",
        )
        assertEquals(expected, names)
    }

    @Test
    fun everyParameterSchemaIsAValidObjectSchema() {
        val allowedTypes = setOf("string", "integer", "boolean", "array")

        for (definition in definitions) {
            val parameters = definition.parameters
            assertEquals(definition.name, "object", parameters.text("type"))

            val properties = parameters["properties"]?.jsonObject
            requireNotNull(properties) { "${definition.name} has no properties" }
            for ((name, property) in properties) {
                val type = property.jsonObject.text("type")
                assertTrue("${definition.name}.$name has type $type", type in allowedTypes)
                assertTrue("${definition.name}.$name is not snake_case", SNAKE_CASE.matches(name))

                val values = property.jsonObject["enum"]?.jsonArray
                if (values != null) {
                    assertTrue("${definition.name}.$name has an empty enum", values.isNotEmpty())
                }
            }

            val required = parameters["required"]?.jsonArray ?: JsonArray(emptyList())
            for (requiredName in required) {
                val name = requiredName.jsonPrimitive.content
                assertTrue("${definition.name} requires unknown $name", name in properties)
            }
        }
    }

    @Test
    fun namesAndDescriptionsStayShort() {
        for (definition in definitions) {
            assertTrue(definition.name, SNAKE_CASE.matches(definition.name))
            assertTrue(definition.name, definition.description.length <= 90)
            assertTrue(definition.name, definition.description.endsWith("."))
        }
    }

    /**
     * Every definition sits in the system prompt of every conversation. The chat template renders each
     * as one JSON line with Python's separators; measured with the model's tokenizer the list is about
     * 1,080 tokens, ~750 of them JSON structure (about 42 a tool). The bound stops it from growing; the
     * text is written to build/ so it can be counted with the tokenizer again.
     */
    @Test
    fun theToolListFitsTheSystemPromptBudget() {
        val lines = definitions.map { definition -> templateJson(definition) }
        val rendered = lines.joinToString("\n")

        val outputFile = File("build/tmp/assistant-tools/tool-definitions.txt")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(rendered)

        assertTrue("The tool list is ${rendered.length} characters", rendered.length <= 3_900)
    }

    /** Like the chat template's tool_to_json: Python json.dumps separators, keys in order. */
    private fun templateJson(definition: ToolDefinition): String {
        val name = jsonText(JsonPrimitive(definition.name))
        val description = jsonText(JsonPrimitive(definition.description))
        val parameters = jsonText(definition.parameters)
        return "{\"name\": $name, \"description\": $description, \"parameters\": $parameters}"
    }

    private fun jsonText(element: JsonElement): String {
        return when (element) {
            is JsonObject -> {
                val members = element.map { (key, value) ->
                    val keyText = jsonText(JsonPrimitive(key))
                    val valueText = jsonText(value)
                    "$keyText: $valueText"
                }
                members.joinToString(", ", prefix = "{", postfix = "}")
            }

            is JsonArray -> {
                val items = element.map { item -> jsonText(item) }
                items.joinToString(", ", prefix = "[", postfix = "]")
            }

            is JsonPrimitive -> element.toString()
        }
    }

    private fun JsonObject.text(key: String): String? {
        return this[key]?.jsonPrimitive?.content
    }

    private companion object {
        val SNAKE_CASE = Regex("[a-z]+(_[a-z]+)*")
    }
}
