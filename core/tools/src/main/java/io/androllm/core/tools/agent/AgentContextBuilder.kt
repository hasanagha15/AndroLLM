package io.androllm.core.tools.agent

import io.androllm.core.tools.origin.currentRemoteOrigin
import io.androllm.engine.models.ChatPromptMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the "CURRENT CONTEXT" block that is injected in front of every
 * tool-planning round (local planner prompt and cloud system message):
 *
 * 1. Live device facts from [DeviceContextProvider] (time, battery, clipboard,
 *    foreground app, …) so the model never asks for them.
 * 2. Workflow variables written by tools earlier in the same turn, so the
 *    output of one tool becomes the input of the next automatically.
 *
 * The block is compact (bounded) so it never blows up the planner context.
 */
@Singleton
class AgentContextBuilder @Inject constructor(
    private val deviceContext: DeviceContextProvider,
    private val variables: AgentVariableStore
) {

    /** Renders the full context block; empty when there is nothing to add. */
    fun buildBlock(): String = render(deviceContext.collect(), variables.snapshot())

    /**
     * Context for the CURRENT turn: a remote (Jarvis) turn gets no private
     * device facts (clipboard, foreground app) and only its own workflow
     * variables — never the owner's.
     */
    suspend fun buildBlockForCurrentOrigin(): String {
        val remote = currentRemoteOrigin() ?: return buildBlock()
        return render(deviceContext.collect(includePrivate = false), remote.variablesSnapshot())
    }

    suspend fun systemMessageForCurrentOrigin(): ChatPromptMessage = wrap(buildBlockForCurrentOrigin())

    private fun render(facts: List<String>, vars: Map<String, String>): String {
        val sb = StringBuilder()
        if (facts.isNotEmpty()) {
            sb.append("CURRENT CONTEXT (collected automatically — use these instead of asking the user):\n")
            facts.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (vars.isNotEmpty()) {
            sb.append("WORKFLOW VARIABLES (written by tools earlier in this task; prefer them over guessing):\n")
            vars.entries.sortedBy { it.key }.forEach { (k, v) ->
                sb.append("- ").append(k).append(": ").append(v.take(160)).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** System message form for the cloud chat history. */
    fun systemMessage(): ChatPromptMessage = wrap(buildBlock())

    private fun wrap(block: String): ChatPromptMessage {
        if (block.isBlank()) return ChatPromptMessage(role = "system", content = "")
        return ChatPromptMessage(
            role = "system",
            content = "You are operating as an agent on the user's Android device. " +
                "The following facts and working variables are available — use them instead of asking the user.\n\n$block"
        )
    }
}
