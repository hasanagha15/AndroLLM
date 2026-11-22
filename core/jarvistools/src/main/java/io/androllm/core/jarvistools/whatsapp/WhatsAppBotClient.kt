package io.androllm.core.jarvistools.whatsapp

import io.androllm.core.jarvistools.net.BaseUrlPolicy
import io.androllm.core.jarvistools.net.HttpRequest
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.net.HttpTransport
import io.androllm.core.jarvistools.settings.JarvisEndpoints
import io.androllm.core.jarvistools.vault.JarvisSecret
import io.androllm.core.jarvistools.vault.SecretStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/** A pending WhatsApp message as the bot reports it (fields are UNTRUSTED third-party data). */
data class PendingMessage(
    val id: Long,
    val phone: String,
    val name: String,
    val summary: String,
    val urgency: String,
    val options: List<String>
)

/**
 * Jarvis WhatsApp bot (wa-bot container on pi-vision, FastAPI :8090), reached
 * over Tailscale (http://pi-vision.example.ts.net:8090), Bearer JARVIS_API_TOKEN:
 *   GET  /api/status                 mode, paused, pending count, sent_this_month, free_limit
 *   GET  /api/pending                {"items":[{id, phone, name, summary, options[], urgency, created}]}
 *   GET  /api/inbox?limit=N          {"items":[{phone, name, text, kind, ts}]}  (N <= 50)
 *   POST /api/reply {id, text}       sends a reply to a PENDING suggestion -> {"ok","to","text"} | 409 {"ok":false,"error"}
 * Deliberately NOT used: /api/command (free text, can send/change mode) and /api/mode.
 * The app always sends `text` (the exact text the owner confirmed), never `option`.
 */
@Singleton
class WhatsAppBotClient @Inject constructor(
    private val transport: HttpTransport,
    private val secrets: SecretStore,
    private val endpoints: JarvisEndpoints
) {
    sealed interface Outcome {
        data class Ok(val data: JsonObject) : Outcome
        data class Error(val message: String) : Outcome
    }

    private data class Target(val base: HttpUrl, val token: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun target(): Pair<Target?, String?> {
        val parsed = BaseUrlPolicy.parseWhatsApp(endpoints.whatsappBaseUrl)
        if (parsed is BaseUrlPolicy.Parsed.Invalid) return null to "WhatsApp bot URL is invalid (${parsed.reason}) — fix it in Settings → Jarvis tools."
        parsed as BaseUrlPolicy.Parsed.Ok
        val token = secrets.get(JarvisSecret.WHATSAPP_API_TOKEN, parsed.origin) ?: return null to NO_TOKEN
        return Target(parsed.url, token) to null
    }

    /** Null when a request could be made right now, otherwise why not. */
    fun ready(): String? = target().second

    private fun url(base: HttpUrl, path: String, limit: Int? = null): HttpUrl =
        base.newBuilder().encodedPath("/").addPathSegments(path).apply {
            if (limit != null) addQueryParameter("limit", limit.coerceIn(1, 50).toString())
        }.build()

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    fun buildStatusRequest(base: HttpUrl, token: String) = HttpRequest("GET", url(base, "api/status"), auth(token))
    fun buildPendingRequest(base: HttpUrl, token: String) = HttpRequest("GET", url(base, "api/pending"), auth(token))
    fun buildInboxRequest(base: HttpUrl, token: String, limit: Int) =
        HttpRequest("GET", url(base, "api/inbox", limit), auth(token))

    /** Always the exact confirmed [text] — never a draft number that could change on the bot. */
    fun buildReplyRequest(base: HttpUrl, token: String, id: Long, text: String): HttpRequest {
        require(id > 0) { "id must be positive" }
        require(text.isNotBlank() && text.length <= MAX_TEXT) { "text must be 1..$MAX_TEXT chars" }
        val body = buildJsonObject {
            put("id", id)
            put("text", text)
        }
        return HttpRequest("POST", url(base, "api/reply"), auth(token) + ("Content-Type" to "application/json"), body.toString())
    }

    suspend fun status() = get { b, t -> buildStatusRequest(b, t) }
    suspend fun pending() = get { b, t -> buildPendingRequest(b, t) }
    suspend fun inbox(limit: Int) = get { b, t -> buildInboxRequest(b, t, limit) }

    /** Looks up one pending message; Error when it does not exist (anymore). */
    suspend fun findPending(id: Long): Pair<PendingMessage?, String?> {
        val r = pending()
        if (r is Outcome.Error) return null to r.message
        val items = ((r as Outcome.Ok).data["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val item = items.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == id }
            ?: return null to "No pending WhatsApp message #$id (already answered or wrong id) — check whatsapp_pending."
        fun s(k: String) = (item[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val options = (item["options"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return PendingMessage(id, s("phone"), s("name"), s("summary"), s("urgency"), options) to null
    }

    suspend fun reply(id: Long, text: String): Outcome {
        val (t, err) = target()
        if (t == null) return Outcome.Error(err!!)
        val req = runCatching { buildReplyRequest(t.base, t.token, id, text) }
            .getOrElse { return Outcome.Error("Bad reply arguments: ${it.message}") }
        // Never retried: the message may already be out.
        val resp = call(req) ?: return Outcome.Error(NETWORK)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        if (resp.isSuccessful && root["ok"] == JsonPrimitive(true)) return Outcome.Ok(root)
        val e = (root["error"] as? JsonPrimitive)?.contentOrNull ?: (root["detail"] as? JsonPrimitive)?.contentOrNull
        return Outcome.Error(if (resp.code == 401) httpMessage(resp) else "WhatsApp bot: ${e?.take(200) ?: httpMessage(resp)}")
    }

    private suspend fun get(build: (HttpUrl, String) -> HttpRequest): Outcome {
        val (t, err) = target()
        if (t == null) return Outcome.Error(err!!)
        val resp = call(build(t.base, t.token)) ?: return Outcome.Error(NETWORK)
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
        const val NO_TOKEN = "WhatsApp bot token is not set for this URL — enter it in Settings → Jarvis tools."
        const val NETWORK = "WhatsApp bot is unreachable — it is reached over Tailscale (turn Tailscale on)."
    }
}
