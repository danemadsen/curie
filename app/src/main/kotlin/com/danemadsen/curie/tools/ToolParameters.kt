package com.danemadsen.curie.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the JSON schema "parameters" object of a tool, keeping properties in declaration order. */
internal fun toolParameters(declare: ToolParametersBuilder.() -> Unit): JsonObject {
    val builder = ToolParametersBuilder()
    builder.declare()
    return builder.build()
}

/**
 * Every tool definition sits in the system prompt of every conversation, so descriptions stay short
 * and only appear where the property name alone is not enough.
 */
internal class ToolParametersBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val requiredNames = mutableListOf<String>()

    fun string(name: String, description: String? = null, required: Boolean = false) {
        val property = property("string", description, values = null)
        add(name, property, required)
    }

    fun integer(name: String, description: String? = null, required: Boolean = false) {
        val property = property("integer", description, values = null)
        add(name, property, required)
    }

    fun boolean(name: String, description: String? = null, required: Boolean = false) {
        val property = property("boolean", description, values = null)
        add(name, property, required)
    }

    fun choice(name: String, values: List<String>, description: String? = null, required: Boolean = false) {
        val property = property("string", description, values)
        add(name, property, required)
    }

    fun stringList(name: String, description: String? = null, required: Boolean = false) {
        val items = property("string", description = null, values = null)
        val property = buildJsonObject {
            put("type", "array")
            if (description != null) {
                put("description", description)
            }
            put("items", items)
        }
        add(name, property, required)
    }

    fun choiceList(name: String, values: List<String>, description: String? = null, required: Boolean = false) {
        val items = property("string", description = null, values)
        val property = buildJsonObject {
            put("type", "array")
            if (description != null) {
                put("description", description)
            }
            put("items", items)
        }
        add(name, property, required)
    }

    fun build(): JsonObject {
        val propertiesObject = JsonObject(properties)
        val requiredPrimitives = requiredNames.map { name -> JsonPrimitive(name) }
        val requiredArray = JsonArray(requiredPrimitives)

        return buildJsonObject {
            put("type", "object")
            put("properties", propertiesObject)
            if (requiredNames.isNotEmpty()) {
                put("required", requiredArray)
            }
        }
    }

    private fun add(name: String, property: JsonObject, required: Boolean) {
        require(name !in properties) { "Parameter $name is declared twice" }
        properties[name] = property
        if (required) {
            requiredNames.add(name)
        }
    }

    private fun property(type: String, description: String?, values: List<String>?): JsonObject {
        return buildJsonObject {
            put("type", type)
            if (description != null) {
                put("description", description)
            }
            if (values != null) {
                val valuePrimitives = values.map { value -> JsonPrimitive(value) }
                val valueArray = JsonArray(valuePrimitives)
                put("enum", valueArray)
            }
        }
    }
}
