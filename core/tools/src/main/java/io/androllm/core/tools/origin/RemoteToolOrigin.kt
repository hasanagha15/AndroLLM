package io.androllm.core.tools.origin

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolSpec
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context marker for agent work that was NOT typed or spoken by
 * the owner at the phone, but arrived over the network (Jarvis link, MQTT).
 *
 * The runner sets it with `withContext(RemoteToolOrigin())` around the chat
 * turn; every coroutine below (channelFlow, async tool batches, the executor)
 * inherits it. [io.androllm.core.tools.executor.ToolExecutor] and
 * [io.androllm.core.tools.confirmation.ToolConfirmationManager] read it to
 * apply [RemoteToolPolicy]. Typed/voice turns never carry it, so their
 * behaviour is unchanged.
 */
class RemoteToolOrigin(
    /** Shown on confirmation cards / notifications. */
    val label: String = DEFAULT_LABEL,
    /** Package names of this app — remote calls aimed at them are refused. */
    val ownPackages: Set<String> = emptySet()
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RemoteToolOrigin> {
        const val DEFAULT_LABEL = "Jarvis (удалённо)"
    }
}

/**
 * Policy for remote-origin tool calls (stricter than any user setting):
 * - only pure-read tools ([ToolSpec.cacheable], never `ui_*` / `mcp_*`) run
 *   without asking — weather, calculator, battery, web search;
 * - EVERYTHING else needs an explicit confirmation, whatever the confirmation
 *   mode or the tool's risk flag says;
 * - confirmation mode NEVER refuses remote calls outright (checked on every call);
 * - calls that target this app itself (its package / name in the arguments)
 *   are refused, so a remote task cannot drive AndroLLM's own settings.
 */
object RemoteToolPolicy {

    fun isAutoAllowed(spec: ToolSpec): Boolean =
        spec.cacheable &&
            !spec.requiresConfirmation &&
            !spec.name.startsWith("ui_") &&
            !spec.name.startsWith("mcp_")

    fun targetsOwnApp(call: ToolCall, origin: RemoteToolOrigin): Boolean {
        val args = call.arguments.toString().lowercase()
        if ("androllm" in args) return true
        return origin.ownPackages.any { it.isNotBlank() && it.lowercase() in args }
    }

    /** Full (not 40-char) argument rendering for remote confirmations. */
    fun describeArguments(call: ToolCall, maxPerArg: Int = 600): String =
        call.arguments.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().take(maxPerArg)}" }
}
