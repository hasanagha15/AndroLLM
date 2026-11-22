package io.androllm.core.tools.prompt

import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.planner.ToolPlanner
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Prompt Builder — the component that makes sure the MODEL knows exactly which
 * tools exist. Every chat turn (text chat and voice, local and cloud) injects
 * the block produced here into the system prompt, so the model never answers
 * "I don't have access to a web search tool" — the tool list is part of its
 * instructions.
 *
 * The block is intentionally compact (one line per tool) so ~45 tools stay
 * well inside the context budget. Full JSON schemas stay in the planner
 * prompt ([io.androllm.core.tools.planner.ToolPrompts]); this advertisement
 * gives the model name, arguments and description plus an example payload it
 * can mirror when asked.
 */
@Singleton
class ToolPromptBuilder @Inject constructor(
    private val planner: ToolPlanner
) {

    /**
     * System-message content for the answer generation. Returns null when the
     * tool-calling pipeline is disabled or no tool is available — in that
     * case there is genuinely nothing to advertise.
     *
     * [maxChars] bounds the rendered length (0 = unlimited). Small-context
     * containers (some Qwen3 repacks are 2048 tokens) can be overrun by the
     * tool list alone — the chat trimmer only trims history, never the system
     * prompt, so an unbounded advertisement makes EVERY prompt fail with
     * "token ids are too long". When the budget can't fit every tool, the
     * header stays and tools are dropped from the end (smallest-value-first
     * alphabetical tail) until it fits.
     */
    suspend fun advertisement(maxChars: Int = 0, query: String = "", hasAttachments: Boolean = false): String? {
        // Route the advertisement to the request (spec: never expose every
        // tool simultaneously). Attachment-scoped / math / device / web
        // requests advertise only the relevant subset — an attachment
        // question advertises NOTHING (its content is already injected).
        val specs = planner.routedTools(query, hasAttachments)
        if (specs.isEmpty()) {
            if (query.isNotBlank()) {
                Timber.i("ToolPromptBuilder: no tools advertised for this request (query routed empty)")
            }
            return null
        }
        return render(specs, maxChars)
    }

    /**
     * Pure renderer (unit-testable): tool list → advertisement text.
     *
     * With a budget ([maxChars] > 0) that the full list does not fit, the
     * lines are rendered COMPACT first (shorter description, no example; the
     * argument names and enum values stay) so far more tools fit before any
     * is dropped from the tail. Callers pass the specs most-relevant-first
     * (the router orders them by the request), so the tail is the least
     * relevant part.
     */
    fun render(specs: List<ToolSpec>, maxChars: Int = 0): String {
        val full = renderLines(specs, maxChars, compact = false)
        if (maxChars <= 0 || full.second == specs.size) return full.first
        val compact = renderLines(specs, maxChars, compact = true)
        return if (compact.second > full.second) compact.first else full.first
    }

    /** Renders header + as many lines as fit; returns (text, tools rendered). */
    private fun renderLines(specs: List<ToolSpec>, maxChars: Int, compact: Boolean): Pair<String, Int> {
        val sb = StringBuilder()
        sb.append(HEADER)
        var count = 0
        for (spec in specs) {
            // Budget guard: keep the header + tools until the budget is
            // exhausted. The header is ~600 chars; a 2048-token container
            // gets only ~2K chars in total — enough for the most relevant
            // tools only.
            if (maxChars > 0 && sb.length >= maxChars) break
            val line = StringBuilder()
            line.append("- ").append(spec.name)
            line.append(" — ").append(spec.description.take(if (compact) COMPACT_DESCRIPTION_CHARS else DESCRIPTION_CHARS))
            val params = describeParameters(spec.parameters)
            if (params.isNotBlank()) line.append(" | arguments: ").append(params)
            if (!compact) {
                val example = exampleArguments(spec.parameters)
                if (example.isNotBlank()) line.append(" | example: ").append(example)
            }
            line.append('\n')
            if (maxChars > 0 && sb.length + line.length > maxChars) break
            sb.append(line)
            count++
        }
        return sb.toString() to count
    }

    /**
     * Compact "key (type), key (type)" listing of the JSON-schema properties.
     * Enum-constrained strings list their allowed values ("action (string:
     * lock|climate_on|…)") so a local model sees every permitted action even
     * when the description is cut.
     */
    private fun describeParameters(schema: JsonObject): String {
        val props = schema["properties"] as? JsonObject ?: return ""
        return props.mapNotNull { (name, el) ->
            val obj = el as? JsonObject
            val type = obj?.get("type")?.let {
                (it as? JsonPrimitive)?.content
            } ?: "any"
            val enumValues = (obj?.get("enum") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (enumValues.isEmpty()) {
                "$name ($type)"
            } else {
                val shown = enumValues.take(MAX_ENUM_VALUES).joinToString("|")
                val more = if (enumValues.size > MAX_ENUM_VALUES) "|…" else ""
                "$name ($type: $shown$more)"
            }
        }.joinToString(", ")
    }

    /**
     * Builds a minimal-but-valid example argument object from the schema:
     * every required property gets a type-appropriate placeholder, so the
     * model can mirror the shape. Empty when the tool takes no arguments.
     */
    private fun exampleArguments(schema: JsonObject): String {
        val props = schema["properties"] as? JsonObject ?: return ""
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val keys = if (required.isNotEmpty()) required else props.keys.toList()
        if (keys.isEmpty()) return ""
        val parts = keys.take(4).mapNotNull { key ->
            val el = props[key] as? JsonObject ?: return@mapNotNull null
            val placeholder = when ((el["type"] as? JsonPrimitive)?.content) {
                "string" -> "\"value\""
                "integer", "number" -> "0"
                "boolean" -> "true"
                "array" -> "[]"
                "object" -> "{}"
                else -> "\"value\""
            }
            "\"$key\": $placeholder"
        }
        return if (parts.isEmpty()) "" else "{${parts.joinToString(", ")}}"
    }

    companion object {
        /** Description length in the normal line (cloud / roomy contexts). */
        const val DESCRIPTION_CHARS = 140

        /** Description length in the compact line used when the budget is tight. */
        const val COMPACT_DESCRIPTION_CHARS = 70

        /** Enum values listed per argument before "|…". */
        const val MAX_ENUM_VALUES = 12

        private const val HEADER =
            "You are the assistant of an on-device AI agent and you HAVE access to tools. " +
                "The tools below are available RIGHT NOW — never claim you lack access to them.\n" +
                "When the user's request involves an app, the web, the device, or communication " +
                "(weather, search, opening apps, sending messages/calls/emails, clipboard, " +
                "screenshots, music, settings, alarms, notes, files, calculator, translation, " +
                "GitHub, image generation, …), use the matching tool and summarize its result " +
                "for the user. Never say \"I can't do that\" when a tool exists for it.\n\n" +
                "AVAILABLE TOOLS (name — description | arguments: key (type) | example):\n"
    }
}
