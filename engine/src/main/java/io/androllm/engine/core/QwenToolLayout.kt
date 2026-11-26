package io.androllm.engine.core

import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.ChatPromptToolCall

/**
 * Branch U: tool turns rendered exactly like the official Qwen3 chat template
 * (the app's Qwen override renders strings, so the planner writes the same text
 * the HF template would):
 *
 * - assistant: `content` + for each call a newline, then
 *   `<tool_call>` NL `{"name": "N", "arguments": ARGS}` NL `</tool_call>`
 *   (no leading newline when the content is empty and it is the first call);
 * - tool results: ONE user turn of `<tool_response>` NL RESULT NL `</tool_response>`
 *   blocks joined by a newline.
 */
internal object QwenToolLayout {

    private const val NL = "\n"

    fun assistantWithCalls(content: String, calls: List<ChatPromptToolCall>): String = buildString {
        append(content)
        calls.forEachIndexed { i, call ->
            if (i > 0 || content.isNotEmpty()) append(NL)
            append("<tool_call>").append(NL)
            append("{\"name\": \"").append(call.name).append("\", \"arguments\": ")
            append(call.argumentsJson.ifBlank { "{}" })
            append("}").append(NL).append("</tool_call>")
        }
    }

    /** [results]: each tool message with its (already framed) result text. */
    fun responses(results: List<Pair<ChatPromptMessage, String>>, notePrefix: String): String {
        val blocks = results.joinToString(NL) { (_, text) -> "<tool_response>" + NL + text + NL + "</tool_response>" }
        val notes = results.mapNotNull { (m, _) -> m.toolNote?.let { "${m.toolName ?: "tool"}: $it" } }
        return if (notes.isEmpty()) blocks else blocks + NL + notePrefix + notes.joinToString(NL)
    }
}
