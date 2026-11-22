package io.androllm.core.tools.origin

import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolSpec
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Coroutine-context marker for agent work that was NOT typed or spoken by
 * the owner at the phone, but arrived over the network (Jarvis link, MQTT).
 *
 * The runner sets it with `withContext(RemoteToolOrigin())` around the chat
 * turn; every coroutine below (channelFlow, async tool batches, the executor)
 * inherits it. ToolExecutor, ToolConfirmationManager, AgentContextBuilder and
 * ToolRunCoordinator read it to apply [RemoteToolPolicy]. Typed/voice turns
 * never carry it.
 *
 * Each instance also owns a PRIVATE workflow-variable map, so a remote turn
 * never reads or writes the owner's [AgentVariableStore] scope.
 */
class RemoteToolOrigin(
    /** Shown on confirmation cards / notifications. */
    val label: String = DEFAULT_LABEL,
    /** Package names of this app — remote calls aimed at them are refused. */
    val ownPackages: Set<String> = emptySet()
) : AbstractCoroutineContextElement(Key) {

    private val variables = LinkedHashMap<String, String>()

    fun setVariable(key: String, value: String) {
        synchronized(variables) { variables[key.trim()] = value.trim() }
    }

    fun variablesSnapshot(): Map<String, String> = synchronized(variables) { variables.toMap() }

    companion object Key : CoroutineContext.Key<RemoteToolOrigin> {
        const val DEFAULT_LABEL = "Jarvis (удалённо)"
    }
}

/** The remote origin of the current coroutine, or null for the owner's own turns. */
suspend fun currentRemoteOrigin(): RemoteToolOrigin? = currentCoroutineContext()[RemoteToolOrigin]

/** Workflow variables visible to the current turn (remote turns see only their own). */
suspend fun AgentVariableStore.snapshotForCurrentOrigin(): Map<String, String> =
    currentRemoteOrigin()?.variablesSnapshot() ?: snapshot()

/** Writes a workflow variable into the current turn's own scope. */
suspend fun AgentVariableStore.setForCurrentOrigin(key: String, value: String) {
    val remote = currentRemoteOrigin()
    if (remote != null) remote.setVariable(key, value) else set(key, value)
}

/**
 * Policy for remote-origin tool calls — never softer than the owner's own
 * settings:
 * - without a card run ONLY the tools in [AUTO_ALLOWED] (fixed-host reads:
 *   calculator, battery, weather), and only if the owner's mode does not ask
 *   for them anyway ("Always" is honoured). `search_web` is NOT here: a query
 *   can reach arbitrary hosts (DuckDuckGo !bangs) and exfiltrate data;
 * - everything else needs an explicit confirmation;
 * - confirmation mode NEVER refuses remote calls outright (checked every call);
 * - [FORBIDDEN] tools are refused (ui_run: one card would cover many unseen steps);
 * - calls targeting this app itself are refused;
 * - an argument longer than [MAX_ARG_CHARS] is refused, so the card always
 *   shows the COMPLETE text of what would be executed.
 */
object RemoteToolPolicy {

    val AUTO_ALLOWED: Set<String> = setOf("calculate", "get_battery", "get_weather")

    val FORBIDDEN: Set<String> = setOf("ui_run")

    const val MAX_ARG_CHARS = 1_500

    fun isAutoAllowed(spec: ToolSpec): Boolean = spec.name in AUTO_ALLOWED && !spec.requiresConfirmation

    fun targetsOwnApp(call: ToolCall, origin: RemoteToolOrigin): Boolean {
        val args = call.arguments.toString().lowercase()
        if ("androllm" in args) return true
        return origin.ownPackages.any { it.isNotBlank() && it.lowercase() in args }
    }

    fun hasOversizedArgument(call: ToolCall): Boolean =
        call.arguments.values.any { argText(it).length > MAX_ARG_CHARS }

    /** Complete argument rendering for remote confirmations (no truncation — see [hasOversizedArgument]). */
    fun describeArguments(call: ToolCall): String =
        call.arguments.entries.joinToString("\n") { (k, v) -> "$k = ${argText(v)}" }

    private fun argText(v: JsonElement): String =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()
}
