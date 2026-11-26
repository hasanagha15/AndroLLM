package io.androllm.core.tools.coordinator

import io.androllm.core.tools.api.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Per-turn loop protection for tool execution. One instance is created per
 * user turn (chat and voice) and shared across every planning/execution round
 * of that turn, so a confused LLM can never loop a tool indefinitely:
 *
 * - **Total cap** — at most [maxTotalCalls] tool executions per turn.
 * - **Consecutive cap** — at most [maxConsecutiveSameTool] executions of the
 *   SAME tool back to back.
 * - **Dedupe** — a (name, arguments) pair that already executed this turn is
 *   never executed again.
 * - **Disable on failure** — a tool whose calls failed twice (with different
 *   arguments), or that failed with an argument-independent outcome (declined,
 *   permanent status), is disabled for the rest of the turn. A single failed
 *   call blocks only that exact (name, arguments) pair.
 *
 * The chat pipeline asks [canExecute] before every call and calls [record]
 * after execution; when a call is rejected the pipeline injects [stopReason]
 * back into the model context so the model continues reasoning WITHOUT
 * further tool calls (the spec's "abort execution" path).
 */
class ToolLoopGuard(
    val maxTotalCalls: Int = 12,
    val maxConsecutiveSameTool: Int = 2
) {

    private val executedKeys = mutableSetOf<String>()
    private val consecutiveByTool = mutableMapOf<String, Int>()
    private val failuresByTool = mutableMapOf<String, Int>()
    private val disabled = mutableSetOf<String>()
    private var lastTool: String? = null

    /** Total tool executions recorded this turn. */
    var totalCalls: Int = 0
        private set

    /** True when at least one call was rejected by a guard check this turn. */
    var blockedThisTurn: Boolean = false
        private set

    fun isDisabled(name: String): Boolean = name in disabled

    /** Tool names disabled mid-turn (for diagnostics / UI). */
    fun disabledTools(): Set<String> = disabled.toSet()

    /**
     * Whether [name] may execute with [arguments] right now. False when the
     * tool is disabled, a cap is hit, or this exact call already ran.
     */
    fun canExecute(name: String, arguments: JsonObject): Boolean {
        val ok = when {
            name in disabled -> false
            totalCalls >= maxTotalCalls -> false
            (consecutiveByTool[name] ?: 0) >= maxConsecutiveSameTool -> false
            key(name, arguments) in executedKeys -> false
            else -> true
        }
        if (!ok) blockedThisTurn = true
        return ok
    }

    /**
     * Records an execution so subsequent identical/repeated calls are blocked.
     * Call right after the tool ran (success or failure).
     */
    fun record(name: String, arguments: JsonObject, result: ToolResult) {
        totalCalls++
        val key = key(name, arguments)
        executedKeys += key
        if (name == lastTool) {
            consecutiveByTool[name] = (consecutiveByTool[name] ?: 0) + 1
        } else {
            // A different tool broke the previous tool's run — reset its
            // streak so the cap only applies to true back-to-back repeats.
            lastTool?.let { consecutiveByTool[it] = 0 }
            consecutiveByTool[name] = 1
        }
        lastTool = name
        if (result is ToolResult.Failure) {
            val failures = (failuresByTool[name] ?: 0) + 1
            failuresByTool[name] = failures
            // Branch U (step 5): failures are counted per (name, arguments). The
            // failed call itself can never re-run (its key is in executedKeys),
            // but a CORRECTED call — the model fixed the arguments the error
            // message pointed at (app_open_action package="Settings" →
            // "com.android.settings") — may run once. The whole tool is disabled
            // only when the outcome is independent of the arguments (the user
            // declined, a permanent status such as a missing permission) or when
            // two different calls of it failed this turn.
            if (isArgumentIndependent(result) || failures >= 2) disable(name)
        }
    }

    /** Explicitly disables a tool for the remainder of the turn. */
    fun disable(name: String) {
        disabled += name
    }

    /**
     * Message to inject into the model context when calls were blocked, or
     * null when the guard is not currently blocking. Only reports the FIRST
     * blocking condition so the injected message stays concise.
     */
    fun stopReason(): String? {
        if (totalCalls >= maxTotalCalls) {
            return "Tool execution limit reached ($maxTotalCalls calls). The requested tools have already been used and produced no additional useful information. Continue reasoning without further tool calls."
        }
        val blockedTool = consecutiveByTool.entries
            .firstOrNull { it.value >= maxConsecutiveSameTool }
            ?.key
        if (blockedTool != null) {
            return "The tool '$blockedTool' has already been used $maxConsecutiveSameTool times in a row and produced no additional useful information. Continue reasoning without further tool calls."
        }
        return null
    }

    /** Failures no other arguments can fix: declined by the user, permanent status. */
    private fun isArgumentIndependent(result: ToolResult.Failure): Boolean =
        result.summary.startsWith("The user declined") ||
            (!result.retryable && !ToolRunCoordinator.isRetryableFailure(result.copy(retryable = true)))

    private fun key(name: String, arguments: JsonObject): String =
        "$name|${arguments.toString().replace(" ", "")}"
}


