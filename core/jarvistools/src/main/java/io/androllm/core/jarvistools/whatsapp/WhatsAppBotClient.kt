package io.androllm.core.jarvistools.whatsapp

import io.androllm.core.jarvistools.net.HttpRequest
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.net.HttpTransport
import io.androllm.core.jarvistools.secrets.JarvisSecret
import io.androllm.core.jarvistools.secrets.SecretStore
import io.androllm.core.jarvistools.settings.JarvisEndpoints
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Jarvis WhatsApp bot (wa-bot container on pi-vision, FastAPI :8090). The /api
 * routes are LAN-only by design (http://192.168.0.10:8090), Bearer API_TOKEN:
 *   GET  /api/status                 mode, paused, pending count, sent_this_month, free_limit
 *   GET  /api/pending                {"items":[{id, phone, name, summary, options[], urgency, created}]}
 *   GET  /api/inbox?limit=N          {"items":[{phone, name, text, kind, ts}]}  (N <= 50)
 *   POST /api/reply {id, option|text} sends a reply to a PENDING suggestion -> {"ok","to","text"} | 409 {"ok":false,"error"}
 * Deliberately NOT used: /api/command (free-text, can send/change mode) and /api/mode.
 */
@Singleton
class WhatsAppBotClient @Inject constructor(
    private val transport: HttpTransport,
    private val secrets: SecretStore,
    private val endpoints: JarvisEndpoints
) {
    sealed interface Outcome {
        data class Ok(val data: JsonObject) : Outcome
        data class Error(val message: String, val retryable: Boolean = false) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun base() = endpoints.whatsappBaseUrl.trimEnd('/')
    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    fun buildStatusRequest(token: String) = HttpRequest("GET", base() + "/api/status", auth(token))
    fun buildPendingRequest(token: String) = HttpRequest("GET", base() + "/api/pending", auth(token))
    fun buildInboxRequest(token: String, limit: Int) =
        HttpRequest("GET", base() + "/api/inbox?limit=" + limit.coerceIn(1, 50), auth(token))

    /** Exactly one of [option] (1-based draft number) or [text] (own text). */
    fun buildReplyRequest(token: String, id: Long, option: Int?, text: String?): HttpRequest {
        require(id > 0) { "id must be positive" }
        require((option == null) != (text.isNullOrBlank())) { "give either option or text" }
        option?.let { require(it in 1..9) { "option must be 1..9" } }
        val body = buildJsonObject {
            put("id", id)
            if (option != null) put("option", option) else put("text", text!!.trim().take(MAX_TEXT))
        }
        return HttpRequest("POST", base() + "/api/reply", auth(token) + ("Content-Type" to "application/json"), body.toString())
    }

    suspend fun status() = get { buildStatusRequest(it) }
    suspend fun pending() = get { buildPendingRequest(it) }
    suspend fun inbox(limit: Int) = get { buildInboxRequest(it, limit) }

    suspend fun reply(id: Long, option: Int?, text: String?): Outcome {
        val token = secrets.get(JarvisSecret.WHATSAPP_API_TOKEN) ?: return Outcome.Error(NO_TOKEN)
        val req = runCatching { buildReplyRequest(token, id, option, text) }
            .getOrElse { return Outcome.Error("Bad reply arguments: ${it.message}") }
        // Never retried: the message may already be out.
        val resp = call(req) ?: return Outcome.Error(NETWORK)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        if (resp.isSuccessful && root["ok"] == JsonPrimitive(true)) return Outcome.Ok(root)
        val err = (root["error"] as? JsonPrimitive)?.contentOrNull ?: (root["detail"] as? JsonPrimitive)?.contentOrNull
        return Outcome.Error(if (resp.code == 401) httpMessage(resp) else "WhatsApp bot: ${err ?: httpMessage(resp)}")
    }

    private suspend fun get(build: (String) -> HttpRequest): Outcome {
        val token = secrets.get(JarvisSecret.WHATSAPP_API_TOKEN) ?: return Outcome.Error(NO_TOKEN)
        val resp = call(build(token)) ?: return Outcome.Error(NETWORK, retryable = true)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        return if (resp.isSuccessful) Outcome.Ok(root) else Outcome.Error(httpMessage(resp))
    }

    private suspend fun call(req: HttpRequest): HttpResponse? =
        try { transport.execute(req) } catch (e: IOException) { null }

    private fun parse(resp: HttpResponse): JsonObject? =
        runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()

    private fun httpMessage(resp: HttpResponse) = when (resp.code) {
        401 -> "WhatsApp bot rejected the token — re-enter it in Settings → Jarvis tools."
        503 -> "WhatsApp bot has no API token configured."
        else -> "WhatsApp bot error (HTTP ${resp.code})."
    }

    companion object {
        const val MAX_TEXT = 1000
        const val NO_TOKEN = "WhatsApp bot token is not set — enter it in Settings → Jarvis tools."
        const val NETWORK = "WhatsApp bot is unreachable — it is LAN-only (home Wi-Fi, 192.168.0.10)."
    }
}
