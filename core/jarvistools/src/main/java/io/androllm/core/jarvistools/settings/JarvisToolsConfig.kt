package io.androllm.core.jarvistools.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvistools.secrets.JarvisSecret
import io.androllm.core.jarvistools.secrets.SecretStore
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret endpoint configuration (base URLs). Tokens live in [SecretStore]. */
interface JarvisEndpoints {
    val teslaBaseUrl: String
    val whatsappBaseUrl: String
}

data class StaticEndpoints(
    override val teslaBaseUrl: String = JarvisToolsConfig.DEFAULT_TESLA_URL,
    override val whatsappBaseUrl: String = JarvisToolsConfig.DEFAULT_WHATSAPP_URL
) : JarvisEndpoints

/**
 * Settings → Jarvis tools backing store: base URLs in plain prefs, tokens
 * through the Keystore-backed [SecretStore]. The UI only ever learns whether
 * a token is set — never its value.
 */
@Singleton
class JarvisToolsConfig @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore
) : JarvisEndpoints {

    private val prefs = context.getSharedPreferences("jarvistools_config", Context.MODE_PRIVATE)

    override val teslaBaseUrl: String
        get() = prefs.getString(KEY_TESLA_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TESLA_URL

    override val whatsappBaseUrl: String
        get() = prefs.getString(KEY_WA_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WHATSAPP_URL

    fun setTeslaBaseUrl(url: String) = prefs.edit().putString(KEY_TESLA_URL, normalizeUrl(url)).apply()
    fun setWhatsappBaseUrl(url: String) = prefs.edit().putString(KEY_WA_URL, normalizeUrl(url)).apply()

    fun hasToken(secret: JarvisSecret): Boolean = secrets.has(secret)
    fun setToken(secret: JarvisSecret, value: String) = secrets.put(secret, value)
    fun clearToken(secret: JarvisSecret) = secrets.clear(secret)

    companion object {
        /** tesla-bridge via the Cloudflare tunnel on pi-brain. */
        const val DEFAULT_TESLA_URL = "https://tesla.lilab.ai"
        /** wa-bot /api is LAN-only by design (wa.lilab.ai publishes only /webhook, /v1, /health). */
        const val DEFAULT_WHATSAPP_URL = "http://192.168.0.10:8090"
        private const val KEY_TESLA_URL = "tesla_base_url"
        private const val KEY_WA_URL = "whatsapp_base_url"

        fun normalizeUrl(url: String): String = url.trim().trimEnd('/')
    }
}
