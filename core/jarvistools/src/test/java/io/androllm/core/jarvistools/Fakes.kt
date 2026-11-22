package io.androllm.core.jarvistools

import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.jarvistools.net.HttpRequest
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.net.HttpTransport
import io.androllm.core.jarvistools.vault.JarvisSecret
import io.androllm.core.jarvistools.vault.SecretStore
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import kotlinx.serialization.json.JsonObject

class FakeTransport(var response: HttpResponse = HttpResponse(200, """{"ok":true}""")) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    /** Optional per-path responses (encodedPath -> response). */
    val byPath = mutableMapOf<String, HttpResponse>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return byPath[request.url.encodedPath] ?: response
    }
}

/** Origin-bound like the real store: a token is returned only for the origin it was put for. */
class FakeSecrets : SecretStore {
    private val values = mutableMapOf<JarvisSecret, Pair<String, String>>()
    override fun get(secret: JarvisSecret, origin: String) = values[secret]?.takeIf { it.second == origin }?.first
    override fun put(secret: JarvisSecret, value: String, origin: String) { values[secret] = value to origin }
    override fun clear(secret: JarvisSecret) { values.remove(secret) }
    override fun has(secret: JarvisSecret, origin: String) = values[secret]?.second == origin
}

class FakeGate(var approve: Boolean = true) : SideEffectGate {
    val asked = mutableListOf<String>()
    val cards = mutableListOf<PendingToolConfirmation>()
    override suspend fun ensureConfirmed(spec: ToolSpec, arguments: JsonObject): Boolean {
        check(spec.requiresConfirmation)
        asked += spec.name
        return approve
    }
    override suspend fun askDetailed(confirmation: PendingToolConfirmation): Boolean {
        asked += confirmation.toolName
        cards += confirmation
        return approve
    }
}

const val TEST_TOKEN = "test-token-not-real"
