package io.androllm.core.jarvistools.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A fully built HTTP request (pure data, so request building is unit-testable). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: String? = null
) {
    /** Never print the Authorization header. */
    override fun toString(): String = "HttpRequest($method $url, headers=${headers.keys}, body=${jsonBody?.length ?: 0}b)"
}

data class HttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

interface HttpTransport {
    /** Throws [IOException] on network failure. */
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** OkHttp transport, own client: no logging interceptor (tokens must never reach logs). */
@Singleton
class OkHttpTransport @Inject constructor() : HttpTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // a sleeping Tesla can take ~45 s to wake for a command
        .callTimeout(75, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val body = request.jsonBody?.toRequestBody(JSON)
            ?: if (request.method == "POST") ByteArray(0).toRequestBody(null) else null
        val req = Request.Builder().url(request.url).method(request.method, body).apply {
            request.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            HttpResponse(resp.code, resp.body?.string().orEmpty().take(MAX_BODY))
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_BODY = 64_000
    }
}
