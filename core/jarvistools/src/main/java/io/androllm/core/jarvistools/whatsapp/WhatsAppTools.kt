package io.androllm.core.jarvistools.whatsapp

import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.tools.api.PrivacyLevel
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolCost
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

private fun readSpec(name: String, description: String, params: JsonObject = buildJsonObject { put("type", "object") }) = ToolSpec(
    name = name,
    description = description,
    parameters = params,
    permission = null,
    requiresConfirmation = false,
    category = ToolCategory.COMMUNICATION,
    supportedTasks = listOf("whatsapp", "ватсап", "вотсап", "сообщения whatsapp"),
    cacheable = false,
    estimatedLatencyMs = 1500L,
    privacyLevel = PrivacyLevel.SENSITIVE,
    cost = ToolCost.NETWORK,
    failureModes = listOf("network", "auth"),
    capabilities = listOf("whatsapp.read")
)

/** Read-only: bot mode, pending count, free-reply counter. */
@Singleton
class WhatsAppStatusTool @Inject constructor(private val client: WhatsAppBotClient) : Tool {
    override val spec = readSpec(
        "whatsapp_status",
        "Jarvis WhatsApp bot status: mode (suggest/allowlist/all/pause), how many chats wait for a reply, free replies used this month. Read-only."
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = when (val r = client.status()) {
        is WhatsAppBotClient.Outcome.Ok -> ToolResult.Success(
            "WhatsApp bot: mode ${r.data.str("mode")}, paused ${r.data.str("paused")}, pending ${r.data.str("pending")}, " +
                "sent this month ${r.data.str("sent_this_month")}/${r.data.str("free_limit")}.",
            r.data
        )
        is WhatsAppBotClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = r.retryable)
    }
}

/** Read-only: messages waiting for the owner's reply, with the bot's draft options. */
@Singleton
class WhatsAppPendingTool @Inject constructor(private val client: WhatsAppBotClient) : Tool {
    override val spec = readSpec(
        "whatsapp_pending",
        "WhatsApp messages waiting for the owner's reply: id, contact, summary, urgency and the bot's numbered draft replies. Use the id with whatsapp_reply. Read-only."
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = when (val r = client.pending()) {
        is WhatsAppBotClient.Outcome.Ok -> {
            val items = (r.data["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val text = if (items.isEmpty()) "No WhatsApp messages are waiting for a reply." else items.joinToString("\n") { p ->
                val opts = (p["options"] as? JsonArray).orEmpty().mapIndexed { i, o ->
                    "${i + 1}) ${(o as? JsonPrimitive)?.contentOrNull.orEmpty()}"
                }.joinToString(" ")
                "#${p.str("id")} ${p.str("name") ?: p.str("phone")} [${p.str("urgency")}]: ${p.str("summary")} — $opts"
            }
            ToolResult.Success(text, r.data)
        }
        is WhatsAppBotClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = r.retryable)
    }
}

/** Read-only: latest incoming WhatsApp messages. */
@Singleton
class WhatsAppInboxTool @Inject constructor(private val client: WhatsAppBotClient) : Tool {
    override val spec = readSpec(
        "whatsapp_inbox",
        "Latest incoming WhatsApp messages of the owner (newest first). Read-only.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("limit") { put("type", "integer"); put("description", "1..50, default 10") }
            }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val limit = ToolArgs.int(arguments, "limit", 10).coerceIn(1, 50)
        return when (val r = client.inbox(limit)) {
            is WhatsAppBotClient.Outcome.Ok -> {
                val items = (r.data["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val text = if (items.isEmpty()) "WhatsApp inbox is empty." else items.joinToString("\n") {
                    "${it.str("name") ?: it.str("phone")}: ${it.str("text").orEmpty().take(300)}"
                }
                ToolResult.Success(text, r.data)
            }
            is WhatsAppBotClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = r.retryable)
        }
    }
}

/**
 * Side effect: SEND a WhatsApp reply to a pending message (by its id, with a
 * draft number or own text). Always confirmed by the owner. The bot itself
 * only replies to pending suggestions — no arbitrary new chats from here.
 */
@Singleton
class WhatsAppReplyTool @Inject constructor(
    private val client: WhatsAppBotClient,
    private val gate: SideEffectGate
) : Tool {
    override val spec = ToolSpec(
        name = "whatsapp_reply",
        description = "Send a WhatsApp reply to a PENDING message (id from whatsapp_pending): either option = number of the bot's draft, or text = own reply. Always asks the user first.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") { put("type", "integer"); put("description", "Pending message id") }
                putJsonObject("option") { put("type", "integer"); put("description", "Draft number 1..9") }
                putJsonObject("text") { put("type", "string"); put("description", "Own reply text (instead of option)") }
            }
            putJsonArray("required") { add("id") }
        },
        permission = null,
        requiresConfirmation = true,
        category = ToolCategory.COMMUNICATION,
        confirmationPrompt = "send the WhatsApp reply to message {id}",
        supportedTasks = listOf("reply on whatsapp", "ответь в whatsapp", "ответь в ватсап"),
        cacheable = false,
        estimatedLatencyMs = 2500L,
        privacyLevel = PrivacyLevel.SENSITIVE,
        cost = ToolCost.NETWORK,
        failureModes = listOf("network", "auth", "already_handled"),
        dependencies = listOf("whatsapp_pending"),
        capabilities = listOf("whatsapp.send")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = ToolArgs.long(arguments, "id") ?: return ToolResult.Failure("Missing required argument: id", retryable = false)
        val option = ToolArgs.long(arguments, "option")?.toInt()
        val text = ToolArgs.str(arguments, "text", "message")
        if ((option == null) == text.isNullOrBlank()) {
            return ToolResult.Failure("Give either option (draft number) or text, not both/neither.", retryable = false)
        }
        if (!gate.ensureConfirmed(spec, arguments)) {
            return ToolResult.Failure("The user declined the action.", retryable = false)
        }
        return when (val r = client.reply(id, option, text)) {
            is WhatsAppBotClient.Outcome.Ok -> ToolResult.Success("WhatsApp: sent to ${r.data.str("to")}: ${r.data.str("text")}", r.data)
            is WhatsAppBotClient.Outcome.Error -> ToolResult.Failure(r.message, retryable = false)
        }
    }
}
