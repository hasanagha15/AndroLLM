package io.androllm.core.jarvistools

import io.androllm.core.jarvistools.confirm.SideEffectGate
import io.androllm.core.jarvistools.net.HttpRequest
import io.androllm.core.jarvistools.net.HttpResponse
import io.androllm.core.jarvistools.net.HttpTransport
import io.androllm.core.jarvistools.secrets.JarvisSecret
import io.androllm.core.jarvistools.secrets.SecretStore
import io.androllm.core.tools.api.ToolSpec
import kotlinx.serialization.json.JsonObject

class FakeTransport(var response: HttpResponse = HttpResponse(200, """{"ok":true}""")) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return response
    }
}

class FakeSecrets(private val values: MutableMap<JarvisSecret, String> = mutableMapOf()) : SecretStore {
    override fun get(secret: JarvisSecret) = values[secret]
    override fun put(secret: JarvisSecret, value: String) { values[secret] = value }
    override fun clear(secret: JarvisSecret) { values.remove(secret) }
}

class FakeGate(var approve: Boolean = true) : SideEffectGate {
    val asked = mutableListOf<String>()
    override suspend fun ensureConfirmed(spec: ToolSpec, arguments: JsonObject): Boolean {
        check(spec.requiresConfirmation)
        asked += spec.name
        return approve
    }
}

const val TEST_TOKEN = "test-token-not-real"
