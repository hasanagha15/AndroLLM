package io.androllm.engine.core

import io.androllm.engine.models.ChatPromptMessage

/**
 * The chat layer sends several system messages per turn (assistant
 * instructions, personal context, memory, attachments, tool list). LiteRT-LM
 * takes ONE system instruction per conversation, and the engine used to keep
 * only the FIRST system message — silently dropping e.g. the tool list
 * whenever memory context was present (AndroLLM fork F1).
 *
 * [merge] joins every non-blank system message, in order, with a blank line.
 * Returns null when there is none.
 */
internal object SystemPromptMerger {
    fun merge(messages: List<ChatPromptMessage>): String? =
        messages.asSequence()
            .filter { it.role == "system" }
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
}
