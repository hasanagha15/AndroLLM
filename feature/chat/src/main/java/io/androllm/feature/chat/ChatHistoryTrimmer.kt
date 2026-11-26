package io.androllm.feature.chat

import io.androllm.core.models.MessageRole
import kotlin.math.ceil
import kotlin.math.max

/**
 * Sliding-window history trimmer that keeps a chat prompt inside the model's
 * context window.
 *
 * Without trimming, a long conversation eventually exceeds nCtx: the native
 * engine either fails to decode (context overflow — "model never starts" /
 * freeze) or decodes garbage at the clamped tail. This trimmer enforces a
 * token budget **before** the prompt is rendered by dropping the OLDEST
 * messages first — the standard sliding-window policy — while always
 * preserving the current user prompt so the assistant never answers a
 * different question.
 *
 * Pure and side-effect free: the exact policy is exercised by unit tests in
 * `ChatHistoryTrimmerTest`.
 */
object ChatHistoryTrimmer {

    /**
     * Approximate tokens in [text], plus a fixed per-message overhead for the
     * role markers and framing the chat template wraps every message in.
     *
     * Weighting: ASCII ~4 chars/token; non-ASCII (CJK, emoji, accents) costs
     * 1 token per character — the common BPE tokenizers spend 1–2 tokens per
     * CJK character, so a flat `chars / 4` would UNDER-estimate Chinese,
     * Japanese and Korean prompts by 2–4× and the trimmed window could still
     * overflow nCtx (freeze). Deliberately conservative (never under-estimates)
     * so the rendered prompt stays comfortably inside the context window; the
     * native engine owns the exact tokenizer.
     */
    fun estimateTokens(text: String): Int {
        var asciiChars = 0
        var alphabeticChars = 0
        var wideChars = 0
        for (c in text) {
            when {
                c.code < 0x80 -> asciiChars++
                // Branch U (step 5): Cyrillic / Greek / Latin-extended / Armenian…
                // are alphabetic scripts that the Gemma / Qwen tokenizers pack at
                // ~2.5-4 chars per token. Charging 1 token per char (CJK rate)
                // made a ~1 KB Russian system prompt cost ~1K tokens and, with
                // nCtx 4096, trimmed a 3-message chat down to 1 message.
                // Branch U it.2 (Z3): only Latin-extended, Greek, Cyrillic and
                // Armenian (U+0080..U+058F). Other scripts (Devanagari, Thai,
                // Georgian, Ethiopic, Khmer…) often cost >= 1 token per char in
                // byte-level BPE, so they are charged like CJK.
                c.code <= ALPHABETIC_END -> alphabeticChars++
                else -> wideChars++
            }
        }
        return max(
            1,
            ceil(asciiChars / CHARS_PER_TOKEN).toInt() +
                ceil(alphabeticChars / ALPHABETIC_CHARS_PER_TOKEN).toInt() +
                wideChars
        ) + ROLE_OVERHEAD_TOKENS
    }

    /**
     * Returns the chronological sub-list of [history] that fits inside
     * [contextLength] minus [reservedOutputTokens] (tokens kept free for the
     * response) and [systemTokenOverhead] (memory context, tool
     * advertisement, chat-template framing).
     *
     * Invariants (all unit-tested):
     * - The current (last) user message is ALWAYS kept, even when it alone
     *   exceeds the budget — dropping the user's actual question would make
     *   the assistant answer the wrong thing.
     * - Oldest messages are dropped first (the window slides over the tail).
     * - The result starts with a user message: a trim can orphan a leading
     *   assistant reply whose user prompt was dropped, and the chat template
     *   renderer expects alternating roles starting with user.
     */
    fun trim(
        history: List<ChatMessage>,
        contextLength: Int,
        reservedOutputTokens: Int,
        systemTokenOverhead: Int = 0,
        /**
         * Branch U: the smallest output reserve the turn can live with. When the
         * normal budget cannot hold it, the last user+assistant exchange before
         * the current prompt is still kept as long as it (plus the prompt) fits
         * `contextLength - systemTokenOverhead - minOutputTokens` — a short reply
         * like "да" must never lose the question it answers. Default = no
         * relaxation (the old behaviour).
         */
        minOutputTokens: Int = reservedOutputTokens
    ): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        val lastUserIdx = history.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIdx < 0) return emptyList()

        val budget = (contextLength - reservedOutputTokens - systemTokenOverhead)
            .coerceAtLeast(0)
        val greedy = greedyTrim(history, lastUserIdx, budget)
        val exchange = lastExchange(history, lastUserIdx) ?: return greedy
        if (greedy.size >= exchange.size) return greedy
        val relaxedBudget = contextLength - systemTokenOverhead - minOutputTokens.coerceAtMost(reservedOutputTokens)
        val exchangeCost = exchange.sumOf { estimateTokens(it.content) }
        return if (exchangeCost <= relaxedBudget) exchange else greedy
    }

    /** prev user, prev assistant, current user — or null when there is no complete exchange. */
    private fun lastExchange(history: List<ChatMessage>, lastUserIdx: Int): List<ChatMessage>? {
        val prevAssistant = (lastUserIdx - 1 downTo 0).firstOrNull { history[it].role == MessageRole.ASSISTANT }
            ?: return null
        val prevUser = (prevAssistant - 1 downTo 0).firstOrNull { history[it].role == MessageRole.USER }
            ?: return null
        return listOf(history[prevUser], history[prevAssistant], history[lastUserIdx])
    }

    private fun greedyTrim(history: List<ChatMessage>, lastUserIdx: Int, budget: Int): List<ChatMessage> {
        // Budget exhausted before any history could fit: still keep the
        // current prompt — the engine clamps the prompt at nCtx.
        if (budget <= 0) return listOf(history[lastUserIdx])

        // Greedy tail walk: newest first, stop when adding the next (older)
        // message would exceed the budget. The last user message is mandatory.
        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        for (i in history.indices.reversed()) {
            val message = history[i]
            val cost = estimateTokens(message.content)
            if (i != lastUserIdx && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }

        // Drop an orphaned leading assistant reply (its user prompt was
        // trimmed away) so the rendered prompt alternates user→assistant
        // from the first message. The mandatory last user message is never
        // removed by this loop.
        while (kept.isNotEmpty() && kept.first().role != MessageRole.USER) {
            kept.removeFirst()
        }
        return kept.toList()
    }

    private const val CHARS_PER_TOKEN = 4.0
    private const val ALPHABETIC_CHARS_PER_TOKEN = 2.0
    private const val ALPHABETIC_END = 0x058F
    private const val ROLE_OVERHEAD_TOKENS = 4
}
