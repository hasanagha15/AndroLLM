package io.androllm.core.jarvistools.tesla

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

/**
 * tesla-bridge (FastAPI on pi-brain:8090, public as https://tesla.lilab.ai):
 *   GET  /api/status[?fresh=true]   Bearer API_TOKEN -> {"ok", "data": {...}, "error"}
 *   POST /api/command/{name}        Bearer API_TOKEN, JSON params -> {"ok", "action", "error"}
 * Errors from the bridge: forbidden(403) unknown(404) bad_param(400) asleep/budget/not_linked/error(502), 401 bad token, 429.
 */
@Singleton
class TeslaBridgeClient @Inject constructor(
    private val transport: HttpTransport,
    private val secrets: SecretStore,
    private val endpoints: JarvisEndpoints
) {
    sealed interface Outcome {
        data class Ok(val data: JsonObject) : Outcome
        data class Error(val message: String, val retryable: Boolean = false) : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun buildStatusRequest(token: String, fresh: Boolean): HttpRequest = HttpRequest(
        method = "GET",
        url = endpoints.teslaBaseUrl.trimEnd('/') + "/api/status" + if (fresh) "?fresh=true" else "",
        headers = auth(token)
    )

    /**
     * Builds the command request ONLY for an [TeslaSafetyPolicy.Decision.Allowed]
     * decision; there is no code path that turns a raw string into a command URL.
     */
    fun buildCommandRequest(token: String, decision: TeslaSafetyPolicy.Decision.Allowed): HttpRequest {
        // Second lock: re-check right where the URL is made.
        require(TeslaSafetyPolicy.isAllowed(decision.action)) { "action not allowed" }
        val body = buildJsonObject { decision.params.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        return HttpRequest(
            method = "POST",
            url = endpoints.teslaBaseUrl.trimEnd('/') + "/api/command/" + decision.action,
            headers = auth(token) + ("Content-Type" to "application/json"),
            jsonBody = body.toString()
        )
    }

    suspend fun status(fresh: Boolean = false): Outcome {
        val token = secrets.get(JarvisSecret.TESLA_API_TOKEN)
            ?: return Outcome.Error(NO_TOKEN)
        val resp = call(buildStatusRequest(token, fresh)) ?: return Outcome.Error(NETWORK, retryable = true)
        val root = parse(resp) ?: return Outcome.Error(httpMessage(resp))
        if (!resp.isSuccessful || root["ok"] != JsonPrimitive(true)) return Outcome.Error(bridgeError(resp, root))
        val data = (root["data"] as? JsonObject) ?: JsonObject(emptyMap())
        // Coordinates are not needed by the assistant and must not leave via a cloud model.
        return Outcome.Ok(JsonObject(data.filterKeys { it != "latitude" && it != "longitude" }))
    }

    suspend fun command(decision: TeslaSafetyPolicy.Decision.Allowed): Outcome {
        val token = secrets.get(JarvisSecret.TESLA_API_TOKEN)
            ?: return Outcome.Error(NO_TOKEN)
        // Never retried automatically: a command may already have reached the car.
        val resp = call(buildCommandRequest(token, decision)) ?: return Outcome.Error(NETWORK)
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
            "bad token" -> "tesla-bridge rejected the token — re-enter it in Settings → Jarvis tools."
            else -> httpMessage(resp)
        }
    }

    private fun httpMessage(resp: HttpResponse) = when (resp.code) {
        401 -> "tesla-bridge rejected the token — re-enter it in Settings → Jarvis tools."
        429 -> "tesla-bridge rate limit — try again in a minute."
        else -> "tesla-bridge error (HTTP ${resp.code})."
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    companion object {
        const val NO_TOKEN = "Tesla token is not set — enter it in Settings → Jarvis tools."
        const val NETWORK = "tesla-bridge is unreachable (network)."
    }
}
