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

/** What the resident LiteRT conversation was seeded with (reuse key). */
internal data class ResidentSession(
    val systemInstruction: String?,
    val sessionKey: String?,
    val transcript: List<ChatPromptMessage>
)

/**
 * Splits a chat-layer message list into a [ChatTurnPlan].
 *
 * Rules:
 *  - Only the LEADING block of system messages (before the first
 *    user/assistant turn) becomes the system instruction, merged by
 *    [SystemPromptMerger] (F1). Those are app-authored (instructions,
 *    personal context, memory, attachments, tool advertisement).
 *  - A system message AFTER the first turn (tool results: inbox, web pages,
 *    screen text, notifications) is UNTRUSTED data. It is never promoted to
 *    the system instruction; it is carried in the user-role transcript,
 *    wrapped in explicit [DATA_OPEN]/[DATA_CLOSE] markers so it can never
 *    read as a continuation of the owner's words.
 *  - [ChatPromptMessage.ROLE_APP_NOTE] messages (loop-guard note, plain-text
 *    retry instruction) are trusted app notes, labelled as such, unwrapped.
 *  - Consecutive same-role turns are joined, not dropped. This keeps the
 *    user's question and the model's last tool-calling reply in the
 *    transcript during the native tool loop
 *    (`[sys, user Q, assistant A1, system TOOL]` → seed `[Q, A1]`,
 *    prompt `DATA(TOOL)`; `[sys, user Q, system TOOL]` → prompt
 *    `Q + DATA(TOOL)`).
 *  - The total untrusted data is capped at `dataBudgetChars` (newest data
 *    first; truncated blocks are marked), so a huge inbox or web page cannot
 *    overflow the context window on its own.
 */
internal object ChatTurnPlanner {

    private const val JOIN = "\n\n"

    /** Rough chars per token for the data budget (conservative). */
    private const val CHARS_PER_TOKEN = 3.0

    /** Smallest truncated slice worth keeping; below this the block is omitted. */
    private const val MIN_DATA_SLICE = 200

    const val DATA_OPEN = UntrustedDataFraming.DATA_OPEN
    const val DATA_CLOSE = UntrustedDataFraming.DATA_CLOSE
    const val APP_NOTE_PREFIX = UntrustedDataFraming.APP_NOTE_PREFIX

    /** Share of the context for untrusted data on a normal turn. */
    const val NORMAL_DATA_FRACTION = 0.5

    /** Share of the context for untrusted data on an overflow reseed. */
    const val OVERFLOW_DATA_FRACTION = 0.25

    /** Share of the context the whole prompt may take on an overflow reseed. */
    const val OVERFLOW_PROMPT_FRACTION = 0.4

    fun budgetChars(contextTokens: Int, fraction: Double): Int =
        (contextTokens.coerceAtLeast(512) * CHARS_PER_TOKEN * fraction).toInt()

    fun plan(
        messages: List<ChatPromptMessage>,
        dataBudgetChars: Int = Int.MAX_VALUE
    ): ChatTurnPlan {
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

        val dataTexts = capData(rest, dataBudgetChars)
        val turns = mergeSameRole(rest.mapIndexed { i, m ->
            when (m.role) {
                "assistant", "model" -> ChatPromptMessage("assistant", m.content)
                "user" -> ChatPromptMessage("user", m.content)
                ChatPromptMessage.ROLE_APP_NOTE -> ChatPromptMessage("user", APP_NOTE_PREFIX + m.content)
                // Any other non-leading role (system = tool output): untrusted.
                else -> ChatPromptMessage("user", wrapData(dataTexts.getValue(i)))
            }
        })
        val prompt = turns.last().content
        val seed = sanitizeSeed(turns.dropLast(1))
        // F1 gate B1: SystemPromptMerger joins ONLY the leading block.
        return ChatTurnPlan(SystemPromptMerger.merge(messages), seed, prompt)
    }

    /**
     * Branch M (N2): the message list LiteRT effectively renders for [plan]
     * (system instruction, seed, framed prompt) — for the debug prompt mirror,
     * so it shows the framing and the leading-only system block.
     */
    fun mirrorMessages(plan: ChatTurnPlan): List<ChatPromptMessage> =
        listOfNotNull(plan.systemInstruction?.takeIf { it.isNotBlank() }?.let { ChatPromptMessage("system", it) }) +
            plan.seed + ChatPromptMessage("user", plan.prompt)

    /**
     * Transcript the conversation holds after answering [plan] with [reply]
     * (seed + prompt + reply), normalized like a seed so the next turn's
     * reuse check compares like with like.
     *
     * Blank reply (e.g. a tool-call-only round, stored as ""): the live
     * session still holds the prompt (and a model turn), so the transcript
     * keeps the trailing user prompt UNSANITIZED. It ends with a user turn and
     * therefore can never equal a sanitized seed: no later turn (another chat,
     * a remote task) can reuse a session holding text it does not know about.
     */
    fun transcriptAfterReply(plan: ChatTurnPlan, reply: String): List<ChatPromptMessage> {
        val withPrompt = plan.seed + ChatPromptMessage("user", plan.prompt)
        return if (reply.isNotBlank()) {
            sanitizeSeed(withPrompt + ChatPromptMessage("assistant", reply))
        } else {
            withPrompt
        }
    }

    /**
     * KV-reuse decision: same system instruction, same session key (chat /
     * origin) and the resident transcript is exactly this turn's seed.
     */
    fun canReuse(live: ResidentSession, plan: ChatTurnPlan, sessionKey: String): Boolean =
        live.sessionKey == sessionKey &&
            live.systemInstruction == plan.systemInstruction &&
            live.transcript == plan.seed

    /**
     * Context-overflow reseed: keeps only the newest [keepTurns] turns
     * (prompt included), so the seed gets at most `keepTurns - 1` turns.
     */
    fun overflowSeed(plan: ChatTurnPlan, keepTurns: Int): List<ChatPromptMessage> =
        sanitizeSeed(plan.seed.takeLast((keepTurns - 1).coerceAtLeast(0)))

    /**
     * Hard cap for the prompt itself on an overflow reseed (the seed alone
     * cannot fix an oversized prompt). Keeps the head (the owner's words come
     * first) and re-closes an untrusted-data block cut in the middle.
     */
    fun capPrompt(prompt: String, maxChars: Int): String {
        if (prompt.length <= maxChars) return prompt
        val omitted = prompt.length - maxChars
        var head = prompt.take(maxChars) + "\n[truncated: $omitted chars omitted to fit the context window]"
        if (head.lastIndexOf(DATA_OPEN) > head.lastIndexOf(DATA_CLOSE)) {
            head += "\n" + DATA_CLOSE
        }
        return head
    }

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

    private fun isData(m: ChatPromptMessage): Boolean =
        m.role != "user" && m.role != "assistant" && m.role != "model" &&
            m.role != ChatPromptMessage.ROLE_APP_NOTE

    /** Newest data first gets the budget; returns index → (possibly cut) text. */
    private fun capData(rest: List<ChatPromptMessage>, budget: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        var remaining = budget.coerceAtLeast(0)
        for (i in rest.indices.reversed()) {
            val m = rest[i]
            if (!isData(m)) continue
            val text = m.content
            out[i] = when {
                text.length <= remaining -> text.also { remaining -= text.length }
                remaining >= MIN_DATA_SLICE -> {
                    val kept = text.take(remaining)
                    remaining = 0
                    kept + "\n[truncated: ${text.length - kept.length} chars omitted to fit the context window]"
                }
                else -> "[tool output omitted: ${text.length} chars over the context budget]"
            }
        }
        return out
    }

    private fun wrapData(text: String): String =
        // A spoofed marker inside third-party data must not close the block.
        UntrustedDataFraming.wrap(text)

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
