package io.androllm.core.jarvistools.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.jarvistools.net.BaseUrlPolicy
import io.androllm.core.jarvistools.vault.JarvisSecret
import io.androllm.core.jarvistools.vault.SecretStore
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret endpoint configuration (raw base URLs; validated by [BaseUrlPolicy] before every use). */
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
 * through the Keystore-backed [SecretStore], each bound to the origin of the
 * URL it was entered for. Changing a URL therefore makes the token "not set"
 * until the owner enters it again. The UI only learns "set / not set".
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

    /** Saves the URL if valid; returns an error message otherwise (nothing saved). */
    fun setTeslaBaseUrl(url: String): String? = save(KEY_TESLA_URL, BaseUrlPolicy.parseTesla(url))
    fun setWhatsappBaseUrl(url: String): String? = save(KEY_WA_URL, BaseUrlPolicy.parseWhatsApp(url))

    private fun save(key: String, parsed: BaseUrlPolicy.Parsed): String? = when (parsed) {
        is BaseUrlPolicy.Parsed.Invalid -> parsed.reason
        is BaseUrlPolicy.Parsed.Ok -> {
            prefs.edit().putString(key, parsed.url.toString().trimEnd('/')).apply()
            null
        }
    }

    fun originFor(secret: JarvisSecret): String? {
        val parsed = when (secret) {
            JarvisSecret.TESLA_API_TOKEN -> BaseUrlPolicy.parseTesla(teslaBaseUrl)
            JarvisSecret.WHATSAPP_API_TOKEN -> BaseUrlPolicy.parseWhatsApp(whatsappBaseUrl)
        }
        return (parsed as? BaseUrlPolicy.Parsed.Ok)?.origin
    }

    fun hasToken(secret: JarvisSecret): Boolean = originFor(secret)?.let { secrets.has(secret, it) } ?: false

    /** Returns an error message, or null when saved. */
    fun setToken(secret: JarvisSecret, value: String): String? {
        val origin = originFor(secret) ?: return "Fix the URL first."
        return try {
            secrets.put(secret, value, origin)
            null
        } catch (e: Exception) {
            "Could not store the token securely (${e.javaClass.simpleName})."
        }
    }

    fun clearToken(secret: JarvisSecret) = secrets.clear(secret)

    companion object {
        /** tesla-bridge via the Cloudflare tunnel on pi-brain. */
        const val DEFAULT_TESLA_URL = "https://tesla.lilab.ai"
        /** wa-bot over Tailscale (owner decision 23.11): MagicDNS name of pi-vision, port 8090. */
        const val DEFAULT_WHATSAPP_URL = "http://" + BaseUrlPolicy.WHATSAPP_TAILNET_HOST + ":8090"
        private const val KEY_TESLA_URL = "tesla_base_url_v2"
        private const val KEY_WA_URL = "whatsapp_base_url_v2"
    }
}
