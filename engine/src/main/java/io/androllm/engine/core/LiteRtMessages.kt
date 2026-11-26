package io.androllm.engine.core

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import io.androllm.engine.models.ChatPromptMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Branch U: [ChatPromptMessage] → LiteRT-LM [Message], including the native
 * tool turns of Gemma 4 (LiteRT-LM 0.19.1 API: `Message.model(contents,
 * toolCalls, channels)` and `Message.tool(Contents.of(Content.ToolResponse(name,
 * response)))`, rendered by the container template as `<|tool_call>` /
 * `<|tool_response>`).
 */
internal object LiteRtMessages {

    /** One seed (initialMessages) entry. */
    fun seed(m: ChatPromptMessage): Message = when (m.role) {
        "assistant", "model" ->
            if (m.toolCalls.isEmpty()) {
                Message.model(m.content)
            } else {
                Message.model(
                    if (m.content.isEmpty()) Contents.of(emptyList<Content>()) else Contents.of(m.content),
                    m.toolCalls.map { ToolCall(it.name, argumentsMap(it.argumentsJson)) },
                    emptyMap()
                )
            }
        ChatPromptMessage.ROLE_TOOL -> toolResponses(listOf(m))
        else -> Message.user(m.content)
    }

    /** The message that carries the turn: the tool responses, or the user text. */
    fun prompt(plan: ChatTurnPlan, userText: String = plan.prompt): Message =
        if (plan.promptTools.isNotEmpty()) toolResponses(plan.promptTools) else Message.user(userText)

    /** One `tool` message answering every call of the preceding model turn. */
    fun toolResponses(tools: List<ChatPromptMessage>): Message =
        Message.tool(Contents.of(tools.map { Content.ToolResponse(it.toolName ?: "tool", responseValue(it)) }))

    /**
     * Response object of one tool result: `result` = the framed (untrusted) tool
     * output, `note` = the app-authored hint when present.
     */
    fun responseValue(m: ChatPromptMessage): Map<String, Any> = buildMap {
        put("result", m.content)
        m.toolNote?.takeIf { it.isNotBlank() }?.let { put("note", it) }
    }

    /** Call arguments JSON → plain Kotlin map (strings, numbers, booleans, lists, maps). */
    fun argumentsMap(json: String): Map<String, Any> {
        val obj = runCatching { Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in obj) plain(v)?.let { out[k] = it }
        return out
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
        is JsonArray -> e.map { plain(it) }
        is JsonObject -> e.mapValues { (_, v) -> plain(v) }
    }
}
