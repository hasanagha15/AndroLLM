package io.androllm.engine.core

import io.androllm.engine.models.NativeToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Branch P: glue between the app's tool specs and LiteRT-LM's native
 * function-calling API (used for Gemma 4, whose container template renders
 * the declarations as `<|tool>declaration:…<tool|>`).
 *
 * Pure (no LiteRT types) so it is unit-tested on the JVM.
 */
object NativeToolDeclarations {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * OpenAPI tool description the LiteRT `OpenApiTool` contract expects:
     * `{"name":…,"description":…,"parameters":{…}}`. A malformed or empty
     * schema degrades to an argument-less object schema.
     */
    fun descriptionJson(spec: NativeToolSpec): String {
        val params = runCatching { json.parseToJsonElement(spec.parametersJson) as? JsonObject }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }
        return buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", params)
        }.toString()
    }

    /**
     * Gate N1: rough token cost of the declarations the container template
     * renders into the system turn (~4 chars per token, like the chat
     * trimmer's estimate), so the history trimmer reserves room for them.
     */
    fun estimatedTokens(specs: List<NativeToolSpec>): Int =
        specs.sumOf { descriptionJson(it).length + DECLARATION_FRAMING_CHARS } / 4

    /** `<|tool>declaration:` … `<tool|>` and the template's key rewriting. */
    private const val DECLARATION_FRAMING_CHARS = 24

    /**
     * Keeps the most relevant tools (callers pass them most-relevant-first)
     * whose declarations fit [maxChars] (<= 0 = unlimited). At least one tool
     * is kept when any is given, so a tight budget never silently disables
     * function calling.
     */
    fun fitBudget(specs: List<NativeToolSpec>, maxChars: Int): List<NativeToolSpec> {
        if (maxChars <= 0) return specs
        val out = mutableListOf<NativeToolSpec>()
        var used = 0
        for (spec in specs) {
            val cost = descriptionJson(spec).length
            if (out.isNotEmpty() && used + cost > maxChars) break
            out += spec
            used += cost
        }
        return out
    }

    /**
     * Converts one parsed native call (name + the runtime's argument map,
     * whose values are Gson-decoded Kotlin types) into a [NativeToolCall].
     */
    fun toNativeToolCall(name: String, arguments: Map<String, Any?>): NativeToolCall =
        NativeToolCall(name.trim(), JsonObject(arguments.mapValues { toJson(it.value) }).toString())

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            // Gson decodes every JSON number as Double; keep integers integral.
            val d = value.toDouble()
            if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(value)
        }
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }
}
