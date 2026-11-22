package io.androllm.core.jarvistools.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation of the owner-configurable base URLs. A base must be a bare
 * origin: scheme + host (+ port), no user info, no path, no query, no
 * fragment — request paths are then added with [HttpUrl.Builder] only, so a
 * crafted base can never smuggle another command path or a query.
 *
 * - Tesla bridge: HTTPS only.
 * - WhatsApp bot: HTTPS, or plain HTTP only for the pi-vision Tailscale
 *   MagicDNS name (traffic inside the tailnet is WireGuard-encrypted; outside
 *   the tailnet the name does not resolve, so the token goes nowhere). The
 *   same host is the only cleartext exception in jarvis_network_security_config.xml.
 */
object BaseUrlPolicy {

    const val WHATSAPP_TAILNET_HOST = "pi-vision.example.ts.net"
    val CLEARTEXT_HOSTS: Set<String> = setOf(WHATSAPP_TAILNET_HOST)

    sealed interface Parsed {
        data class Ok(val url: HttpUrl) : Parsed {
            /** scheme://host:port — the token is bound to exactly this. */
            val origin: String get() = "${url.scheme}://${url.host}:${url.port}"
        }
        data class Invalid(val reason: String) : Parsed
    }

    fun parseTesla(raw: String?): Parsed = parse(raw) { false }

    fun parseWhatsApp(raw: String?): Parsed = parse(raw) { it.host in CLEARTEXT_HOSTS }

    private fun parse(raw: String?, cleartextOk: (HttpUrl) -> Boolean): Parsed {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Parsed.Invalid("URL is empty")
        val u = text.toHttpUrlOrNull() ?: return Parsed.Invalid("not a valid http(s) URL")
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return Parsed.Invalid("user info is not allowed in the URL")
        if (u.query != null) return Parsed.Invalid("query is not allowed in the URL")
        if (u.fragment != null || text.contains('#')) return Parsed.Invalid("fragment is not allowed in the URL")
        if (u.encodedPath != "/") return Parsed.Invalid("the URL must not contain a path")
        if (!u.isHttps && !cleartextOk(u)) return Parsed.Invalid("only https:// is allowed for this host")
        return Parsed.Ok(u)
    }
}
