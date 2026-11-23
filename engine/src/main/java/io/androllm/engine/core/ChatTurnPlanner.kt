package io.androllm.engine.core

import io.androllm.engine.models.ChatPromptMessage

/**
 * What one LiteRT-LM chat turn is made of: the conversation-level
 * [systemInstruction], the [seed] transcript (`initialMessages`, strict
 * user/assistant alternation starting with user and ending with assistant)
 * and the [prompt] sent as the new user message.
 */
internal data class ChatTurnPlan(
    val systemInstruction: String?,
    val seed: List<ChatPromptMessage>,
    val prompt: String
)

/**
 * Splits a chat-layer message list into a [ChatTurnPlan].
 *
 * Rules:
 *  - Only the LEADING block of system messages (before the first
 *    user/assistant turn) becomes the system instruction, merged by
 *    [SystemPromptMerger] (F1). Those are app-authored (instructions,
 *    personal context, memory, attachments, tool advertisement).
 *  - A system message AFTER the first turn (tool results, loop-guard notes,
 *    plain-text retry) is untrusted data. It is NEVER promoted to the system
 *    instruction; it is carried in the transcript as a user-role message,
 *    exactly like the old "last message is the turn text" path did.
 *  - Consecutive same-role turns are joined, not dropped. This keeps the
 *    user's question and the model's last tool-calling reply in the
 *    transcript during the native tool loop
 *    (`[sys, user Q, assistant A1, system TOOL]` → seed `[Q, A1]`,
 *    prompt `TOOL`; `[sys, user Q, system TOOL]` → prompt `Q + TOOL`).
 *    Before this, every non-leading system message was filtered out and the
 *    trailing user turn was dropped from the seed, so Q and A1 vanished.
 */
internal object ChatTurnPlanner {

    private const val JOIN = "\n\n"

    fun plan(messages: List<ChatPromptMessage>): ChatTurnPlan {
        require(messages.isNotEmpty()) { "Empty message history" }
        val leadingCount = messages.indexOfFirst { it.role != "system" }
            .let { if (it < 0) messages.size else it }
        val leading = messages.subList(0, leadingCount)
        val rest = messages.subList(leadingCount, messages.size)

        if (rest.isEmpty()) {
            // Only system messages: the last one is the turn text (legacy
            // behaviour), the ones before it form the instruction.
            val system = SystemPromptMerger.merge(leading.dropLast(1))
            return ChatTurnPlan(system, emptyList(), messages.last().content)
        }

        val turns = mergeSameRole(rest.map { m ->
            when (m.role) {
                "assistant", "model" -> ChatPromptMessage("assistant", m.content)
                // "user" and any non-leading "system" (untrusted tool output).
                else -> ChatPromptMessage("user", m.content)
            }
        })
        val prompt = turns.last().content
        val seed = sanitizeSeed(turns.dropLast(1))
        // F1 gate B1: SystemPromptMerger joins ONLY the leading block.
        return ChatTurnPlan(SystemPromptMerger.merge(messages), seed, prompt)
    }

    /**
     * Transcript the conversation holds after answering [plan] with [reply]
     * (seed + prompt + reply), normalized like a seed so the next turn's
     * reuse check compares like with like.
     */
    fun transcriptAfterReply(plan: ChatTurnPlan, reply: String): List<ChatPromptMessage> {
        val withPrompt = plan.seed + ChatPromptMessage("user", plan.prompt)
        return if (reply.isNotEmpty()) {
            sanitizeSeed(withPrompt + ChatPromptMessage("assistant", reply))
        } else {
            sanitizeSeed(withPrompt)
        }
    }

    /**
     * Context-overflow reseed: keeps only the newest [keepTurns] turns
     * (prompt included), so the seed gets at most `keepTurns - 1` turns.
     */
    fun overflowSeed(plan: ChatTurnPlan, keepTurns: Int): List<ChatPromptMessage> =
        sanitizeSeed(plan.seed.takeLast((keepTurns - 1).coerceAtLeast(0)))

    /**
     * Normalizes a seed: starts with user, strictly alternates, ends with
     * assistant (the caller sends the next user message itself).
     */
    fun sanitizeSeed(turns: List<ChatPromptMessage>): List<ChatPromptMessage> {
        val out = ArrayList<ChatPromptMessage>(turns.size)
        var expectUser = true
        for (m in turns) {
            val isUser = m.role == "user"
            if (isUser != expectUser) continue
            out.add(m)
            expectUser = !expectUser
        }
        if (out.isNotEmpty() && out.last().role == "user") {
            out.removeAt(out.size - 1)
        }
        return out
    }

    private fun mergeSameRole(turns: List<ChatPromptMessage>): List<ChatPromptMessage> {
        val out = ArrayList<ChatPromptMessage>(turns.size)
        for (m in turns) {
            val prev = out.lastOrNull()
            if (prev != null && prev.role == m.role) {
                out[out.size - 1] = prev.copy(content = joinNonBlank(prev.content, m.content))
            } else {
                out.add(m)
            }
        }
        return out
    }

    private fun joinNonBlank(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> a + JOIN + b
    }
}
