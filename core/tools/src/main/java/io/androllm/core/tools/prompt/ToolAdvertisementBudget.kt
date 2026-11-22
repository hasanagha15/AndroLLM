package io.androllm.core.tools.prompt

/**
 * Character budget for the tool advertisement of a LOCAL model turn that has
 * no per-request budgeting of its own (voice turns and remote Jarvis tasks go
 * through ChatManager; the typed chat computes a similar budget itself).
 *
 * The full list is ~11.7K chars (~2.9K tokens) — more than half of a
 * 4096-token window, leaving almost nothing for memory, history, the question
 * and the answer. Rules (pure, unit-tested):
 * - reserve a third of the window for the answer and [HISTORY_FLOOR_TOKENS]
 *   for memory + history + the request; the rest (×4 chars/token) is the
 *   context budget;
 * - windows of [SMALL_CONTEXT_TOKENS] or less additionally cap the list at a
 *   QUARTER of the window ([contextLength] chars ≈ contextLength / 4 tokens);
 * - the model family's measured cap applies on top;
 * - never 0 (0 means "unlimited" to [ToolPromptBuilder.render]).
 */
object ToolAdvertisementBudget {

    const val CHARS_PER_TOKEN = 4
    const val MIN_OUTPUT_RESERVE_TOKENS = 256
    const val HISTORY_FLOOR_TOKENS = 512
    const val SMALL_CONTEXT_TOKENS = 4096

    /** Smallest budget returned: the header (~600 chars) plus a couple of tools. */
    const val MIN_BUDGET_CHARS = 900

    fun charsFor(contextLength: Int, familyCapChars: Int = Int.MAX_VALUE): Int {
        if (contextLength <= 0) {
            return if (familyCapChars in 1 until Int.MAX_VALUE) familyCapChars.coerceAtLeast(MIN_BUDGET_CHARS) else 0
        }
        val reserve = (contextLength / 3).coerceAtLeast(MIN_OUTPUT_RESERVE_TOKENS)
        val byContext = (contextLength - reserve - HISTORY_FLOOR_TOKENS).coerceAtLeast(0).toLong() * CHARS_PER_TOKEN
        var budget = minOf(byContext, familyCapChars.toLong())
        if (contextLength <= SMALL_CONTEXT_TOKENS) {
            budget = minOf(budget, contextLength.toLong())
        }
        return budget.coerceIn(MIN_BUDGET_CHARS.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
