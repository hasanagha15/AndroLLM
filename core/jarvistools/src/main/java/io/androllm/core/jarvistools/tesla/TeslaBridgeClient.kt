package io.androllm.core.jarvistools.tesla

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl

/**
 * tesla-bridge (FastAPI on pi-brain:8090, public as https://tesla.lilab.ai), client "phone":
 *   GET  /api/status[?fresh=true]   Bearer PHONE_TOKEN -> {"ok", "data": {...}, "error"}
 *   POST /api/command/{name}        Bearer PHONE_TOKEN, JSON params -> {"ok", "action", "error"}
 * The bridge itself allows the phone token only these two routes and only tier-A commands
 * (bridge commit 1ab21d5: policy.PHONE_ACTIONS, own rate limits, audit client "phone").
 */
@Singleton
class TeslaBridgeClient @Inject constructor(
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

    /** Validated base + the token bound to its origin, or an error message. */
    private fun target(): Pair<Target?, String?> {
        val parsed = BaseUrlPolicy.parseTesla(endpoints.teslaBaseUrl)
        if (parsed is BaseUrlPolicy.Parsed.Invalid) return null to "Tesla bridge URL is invalid (${parsed.reason}) — fix it in Settings → Jarvis tools."
        parsed as BaseUrlPolicy.Parsed.Ok
        val token = secrets.get(JarvisSecret.TESLA_API_TOKEN, parsed.origin) ?: return null to NO_TOKEN
        return Target(parsed.url, token) to null
    }

    /** True when a request could be made right now (valid URL + token for its origin). */
    fun ready(): String? = target().second

    fun buildStatusRequest(base: HttpUrl, token: String, fresh: Boolean): HttpRequest {
        val url = base.newBuilder().encodedPath("/").addPathSegments("api/status").apply {
            if (fresh) addQueryParameter("fresh", "true")
        }.build()
        return HttpRequest("GET", url, auth(token))
    }

    /**
     * Builds the command request ONLY for an [TeslaSafetyPolicy.Decision.Allowed]
     * decision; the path is made with HttpUrl.Builder from a validated origin.
     */
    fun buildCommandRequest(base: HttpUrl, token: String, decision: TeslaSafetyPolicy.Decision.Allowed): HttpRequest {
        require(TeslaSafetyPolicy.isAllowed(decision.action)) { "action not allowed" }
        val url = base.newBuilder().encodedPath("/").addPathSegments("api/command").addPathSegment(decision.action).build()
        val body = buildJsonObject { decision.params.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        return HttpRequest("POST", url, auth(token) + ("Content-Type" to "application/json"), body.toString())
    }

    suspend fun status(fresh: Boolean = false): Outcome {
        val (t, err) = target()
        if (t == null) return Outcome.Error(err!!)
        val resp = call(buildStatusRequest(t.base, t.token, fresh)) ?: return Outcome.Error(NETWORK)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        if (!resp.isSuccessful || root["ok"] != JsonPrimitive(true)) return Outcome.Error(bridgeError(resp, root))
        val data = (root["data"] as? JsonObject) ?: JsonObject(emptyMap())
        // Coordinates are not needed by the assistant and must not leave via a cloud model.
        return Outcome.Ok(JsonObject(data.filterKeys { it != "latitude" && it != "longitude" }))
    }

    suspend fun command(decision: TeslaSafetyPolicy.Decision.Allowed): Outcome {
        val (t, err) = target()
        if (t == null) return Outcome.Error(err!!)
        // Never retried: a command may already have reached the car.
        val resp = call(buildCommandRequest(t.base, t.token, decision)) ?: return Outcome.Error(NETWORK)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        if (!resp.isSuccessful || root["ok"] != JsonPrimitive(true)) return Outcome.Error(bridgeError(resp, root))
        return Outcome.Ok(root)
    }

    private suspend fun call(req: HttpRequest): HttpResponse? =
        try { transport.execute(req) } catch (e: IOException) { null }

    private fun parse(resp: HttpResponse): JsonObject? =
        runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()

    private fun bridgeError(resp: HttpResponse, root: JsonObject): String {
        val key = (root["error"] as? JsonPrimitive)?.contentOrNull
            ?: (root["detail"] as? JsonPrimitive)?.contentOrNull
        return when (key) {
            "forbidden" -> "The bridge refused this command (disabled for safety)."
            "asleep" -> "The car is waking up — try again in ~30 s."
            "budget" -> "Monthly Tesla API budget is exhausted."
            "not_linked" -> "Tesla is not linked to the bridge."
            "bad_param" -> "The bridge rejected the value."
            "unknown" -> "Unknown command."
            "bad token" -> BAD_TOKEN
            "too many requests" -> RATE
            else -> httpMessage(resp)
        }
    }

    private fun httpMessage(resp: HttpResponse) = when (resp.code) {
        401 -> BAD_TOKEN
        429 -> RATE
        else -> "tesla-bridge error (HTTP ${resp.code})."
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    companion object {
        const val NO_TOKEN = "Tesla token is not set for this URL — enter it in Settings → Jarvis tools."
        const val NETWORK = "tesla-bridge is unreachable (network)."
        const val BAD_TOKEN = "tesla-bridge rejected the token — re-enter it in Settings → Jarvis tools."
        const val RATE = "tesla-bridge rate limit (phone: 6 commands per 10 min) — try again later."
    }
}
