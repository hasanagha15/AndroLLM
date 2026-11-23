package io.androllm.engine.core

import io.androllm.engine.models.ChatPromptMessage

/**
 * The chat layer sends several LEADING system messages per turn (assistant
 * instructions, personal context, memory, attachments, tool list). LiteRT-LM
 * takes ONE system instruction per conversation, and the engine used to keep
 * only the FIRST — silently dropping e.g. the tool list whenever memory
 * context was present (AndroLLM fork F1).
 *
 * [merge] joins ONLY the leading contiguous block of system messages (before
 * the first user/assistant turn). role=system messages later in the list are
 * NOT promoted: they carry tool results (WhatsApp inbox, web pages, screen
 * text, notifications — untrusted third-party data) and loop-guard notes.
 * Giving them system-instruction priority would amplify prompt injection
 * (F1 gate B1). [ChatTurnPlanner] keeps them in the user-role transcript
 * (seed / prompt) as untrusted data, never in the system instruction.
 *
 * Returns null when there is no leading system message.
 */
internal object SystemPromptMerger {
    fun merge(messages: List<ChatPromptMessage>): String? =
        messages.asSequence()
            .takeWhile { it.role == "system" }
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")
}
