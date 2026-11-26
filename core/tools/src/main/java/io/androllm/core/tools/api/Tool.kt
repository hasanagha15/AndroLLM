package io.androllm.core.tools.api

import kotlinx.serialization.json.JsonObject

/**
 * A single capability the assistant can execute on the user's device or over
 * the network. Implementations are plain Android services — they NEVER contain
 * LLM logic. New tools are added by implementing this interface and
 * registering them in [io.androllm.core.tools.registry.ToolRegistry]; nothing
 * else in the framework needs to change (plugin SDK contract).
 */
interface Tool {

    /** Static, model-visible description of this tool. */
    val spec: ToolSpec

    /**
     * Executes the tool with the parsed [arguments]. Must be fast, never block
     * the caller indefinitely (the executor applies a timeout) and always
     * return a [ToolResult] instead of throwing.
     */
    suspend fun execute(arguments: JsonObject): ToolResult

    /**
     * Branch M: a human-readable description when THIS call (not the tool in
     * general) is a risky UI action — tapping Send / Pay / Confirm / Delete,
     * typing text that submits — or null. A non-null value makes the executor
     * ask the owner with a TAP-ONLY card (whatever the confirmation mode, like
     * tesla_command) and run the call inside [io.androllm.core.tools.confirmation.RiskyActionApproval].
     */
    fun riskyAction(arguments: JsonObject): String? = null

    /**
     * Branch U: Android runtime permissions this tool needs that are NOT granted
     * right now (empty = nothing missing). A non-empty list makes the executor
     * raise a permission card first — Approve opens the system permission dialog
     * (ChatScreen requests [io.androllm.core.tools.confirmation.PendingToolConfirmation.requiredPermissions])
     * — instead of running the tool into a certain "permission missing" failure.
     */
    fun missingRuntimePermissions(): List<String> = emptyList()
}
