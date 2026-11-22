package io.androllm.core.jarvistools.glasses

import io.androllm.core.jarvistools.confirm.SideEffectBudget
import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.tools.api.PrivacyLevel
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolBackend
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Side effect: show a short text on the Even G2 (via the Even app's notification mirror). Always confirmed. */
@Singleton
class GlassesShowTextTool @Inject constructor(
    private val display: GlassesDisplay,
    private val gate: SideEffectGate
) : Tool {

    override val spec = ToolSpec(
        name = "glasses_show_text",
        description = "Show a short plain text (max 240 chars) on the owner's Even Realities G2 glasses. Delivered as a phone notification that the Even app mirrors to the glasses. Asks the user first.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string"); put("description", "Text to show, plain, <= 240 chars") }
                putJsonObject("title") { put("type", "string"); put("description", "Optional short title") }
            }
            putJsonArray("required") { add("text") }
        },
        permission = null,
        requiresConfirmation = true,
        category = ToolCategory.DEVICE,
        confirmationPrompt = "show this on the glasses: {text}",
        executionTimeoutMs = SideEffectBudget.ONE_CALL_MS,
        supportedTasks = listOf("glasses", "очки", "even g2", "покажи на очках"),
        cacheable = false,
        supportedBackends = setOf(ToolBackend.LOCAL, ToolBackend.CLOUD),
        estimatedLatencyMs = 500L,
        privacyLevel = PrivacyLevel.SENSITIVE,
        failureModes = listOf("permission", "even_app_not_mirroring"),
        capabilities = listOf("glasses.display")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val text = ToolArgs.str(arguments, "text", "message")
            ?: return ToolResult.Failure("Missing required argument: text", retryable = false)
        val title = ToolArgs.str(arguments, "title") ?: "Jarvis"
        display.unavailableReason()?.let { return ToolResult.Failure(it, retryable = false) }
        if (!gate.ensureConfirmed(spec, arguments)) {
            return ToolResult.Failure("The user declined the action.", retryable = false)
        }
        val err = display.show(title, text)
        return if (err == null) {
            ToolResult.Success(
                "Notification posted on the phone; it appears on the G2 only if the Even app mirrors AndroLLM notifications: " +
                    GlassesText.sanitize(text, GlassesText.MAX_TEXT)
            )
        } else {
            ToolResult.Failure(err, retryable = false)
        }
    }
}
