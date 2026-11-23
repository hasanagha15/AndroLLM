package io.androllm.core.appatlas.index

/**
 * URI schemes and intent actions the atlas must never fire (gate B1). They
 * call, message, mail or pay — effects that only the confirmed tools
 * (make_call, send_sms, send_email; payments: never) may produce. `geo:` is
 * deliberately allowed: it only shows a map, like open_navigation.
 */
object SchemePolicy {

    /** Payment / wallet schemes. An app that declares one is treated as a payment app (sensitive). */
    val PAYMENT_SCHEMES: Set<String> = setOf(
        "upi", "bitcoin", "ethereum", "litecoin", "dogecoin", "monero", "solana", "tron", "bnb", "lightning",
        "tez", "paytmmp", "phonepe", "gpay", "paypal", "venmo", "cashme", "revolut", "wc", "metamask", "trust",
        "sberpay", "bank", "payment", "pay"
    )

    /** Communication schemes — the atlas must not prefill a call / SMS / mail / chat without a card. */
    val COMMUNICATION_SCHEMES: Set<String> = setOf(
        "tel", "telprompt", "callto", "sms", "smsto", "mms", "mmsto", "mailto", "voicemail", "sip", "sips",
        "wtai", "imto", "facetime", "facetime-audio", "skype", "xmpp", "whatsapp", "tg", "viber", "signal"
    )

    /** Transport / local-data schemes that are never app actions. */
    val TRANSPORT_SCHEMES: Set<String> = setOf("file", "content", "data", "javascript", "intent", "android-app", "jar", "about")

    val BLOCKED_SCHEMES: Set<String> = PAYMENT_SCHEMES + COMMUNICATION_SCHEMES + TRANSPORT_SCHEMES

    /** Shortcut intent actions that call, send, delete or (un)install. */
    val BLOCKED_SHORTCUT_ACTIONS: Set<String> = setOf(
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
        "android.intent.action.DIAL",
        "android.intent.action.SENDTO",
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE",
        "android.intent.action.DELETE",
        "android.intent.action.INSTALL_PACKAGE",
        "android.intent.action.UNINSTALL_PACKAGE",
        "android.intent.action.FACTORY_TEST",
        "android.intent.action.MASTER_CLEAR"
    )

    fun isBlockedScheme(scheme: String?): Boolean = scheme != null && scheme.lowercase().trim() in BLOCKED_SCHEMES

    /** Scheme of a raw URI string ("tel:+1" → "tel"), or null. */
    fun schemeOf(uri: String?): String? {
        val u = uri?.trim().orEmpty()
        val i = u.indexOf(':')
        if (i <= 0) return null
        val s = u.substring(0, i)
        return s.takeIf { it.all { c -> c.isLetterOrDigit() || c in "+.-" } }?.lowercase()
    }

    fun isBlockedUri(uri: String?): Boolean = isBlockedScheme(schemeOf(uri))

    fun isBlockedShortcutAction(action: String?): Boolean = action != null && action.trim() in BLOCKED_SHORTCUT_ACTIONS

    // ── Branch M: http(s) share / compose links ─────────────────────────────

    private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")
    private val WHATSAPP_HOSTS = setOf("wa.me", "api.whatsapp.com", "web.whatsapp.com", "whatsapp.com")
    private val OUTLOOK_HOSTS = setOf("outlook.live.com", "outlook.office.com", "outlook.office365.com", "outlook.com")
    private val X_HOSTS = setOf("twitter.com", "x.com", "mobile.twitter.com")

    /** Query parameters that carry a prefilled message / recipient. */
    private val DRAFT_PARAMS = setOf("text", "body", "msg", "message", "to", "cc", "bcc", "subject", "su", "url", "u", "phone", "recipient", "recipients")

    /** Path segments of generic share / compose endpoints. */
    private val SHARE_SEGMENTS = setOf("share", "sharer", "sharer.php", "share.php", "sharing", "share-offsite", "submit")

    /**
     * True when an http(s) URL opens a PREFILLED draft in a messenger / mail /
     * social app ("https://t.me/share/url?text=…", "wa.me/<n>?text=…",
     * "api.whatsapp.com/send?…", Gmail / Outlook / Yahoo compose, X intents,
     * Facebook sharer…). Opening such a link is a communication action — one
     * tap from sending, with text the model chose — so the atlas refuses it
     * and points to the confirmed tools, like the tel:/sms:/mailto: schemes.
     * Unparseable http(s) URLs count as compose (fail closed).
     */
    fun isComposeWebUrl(uri: String?): Boolean {
        val raw = uri?.trim().orEmpty()
        val scheme = schemeOf(raw) ?: return false
        if (scheme != "http" && scheme != "https") return false
        val parsed = runCatching { java.net.URI(raw) }.getOrNull() ?: return true
        val rawHost = (parsed.host ?: return true).lowercase().trimEnd('.').removePrefix("www.")
        // "m.facebook.com" → "facebook.com", but "m.me" (Messenger) stays.
        val host = if (rawHost.startsWith("m.") && rawHost.count { it == '.' } >= 2) rawHost.removePrefix("m.") else rawHost
        val path = (parsed.path ?: "").lowercase()
        val segments = path.split('/').filter { it.isNotEmpty() }
        val query = parsed.query.orEmpty()
        val fragment = parsed.fragment.orEmpty().lowercase()
        val params = query.split('&').mapNotNull { kv ->
            kv.substringBefore('=').trim().lowercase().takeIf { it.isNotEmpty() }
        }.toSet()
        val hasDraftParam = params.any { it in DRAFT_PARAMS }
        fun hostIs(set: Set<String>) = set.any { host == it || host.endsWith(".$it") }
        return when {
            hostIs(TELEGRAM_HOSTS) ->
                segments.firstOrNull() in setOf("share", "msg", "msg_url") ||
                    "text" in params || "url" in params
            hostIs(WHATSAPP_HOSTS) ->
                segments.firstOrNull() in setOf("send", "message") || "text" in params || "phone" in params ||
                    (host == "wa.me" && segments.isNotEmpty() && hasDraftParam)
            host == "mail.google.com" || host == "gmail.com" ->
                "compose" in path || "compose" in fragment || "cm" == paramValue(query, "view") ||
                    "cm" == paramValue(query, "tf") || hasDraftParam
            hostIs(OUTLOOK_HOSTS) -> "compose" in path || "compose" in fragment || hasDraftParam
            host == "mail.yahoo.com" || host.endsWith(".mail.yahoo.com") -> "compose" in path || hasDraftParam
            hostIs(X_HOSTS) -> segments.firstOrNull() == "intent" || "compose" in path || "share" in segments
            host == "m.me" || host == "messenger.com" -> "text" in params || "compose" in path
            host == "vk.com" || host == "ok.ru" -> segments.any { it in SHARE_SEGMENTS } || "share" in path
            host == "facebook.com" || host == "linkedin.com" || host == "reddit.com" || host == "pinterest.com" ->
                segments.any { it in SHARE_SEGMENTS } || "dialog" in segments
            // Generic: a compose endpoint, or a share endpoint carrying a prefilled text / link.
            else -> segments.any { it == "compose" || it.startsWith("compose.") } ||
                "compose" in fragment ||
                (segments.any { it in SHARE_SEGMENTS } && hasDraftParam) ||
                (segments.firstOrNull() == "intent" && segments.getOrNull(1) in setOf("tweet", "post"))
        }
    }

    private fun paramValue(query: String, name: String): String? =
        query.split('&').firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")?.lowercase()
}

/**
 * Normalizes text that comes from third-party apps (labels, shortcut titles,
 * activity labels, UI strings, URIs) before it is stored or shown to the
 * model (gate M1): control/format characters and line breaks become spaces,
 * tool-call-like markup characters are removed, length is capped.
 */
object AtlasText {

    private val CONTROL = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+")
    private val MARKUP = Regex("[{}\\[\\]<>`\"]+")
    private val SPACES = Regex("\\s+")

    fun clean(raw: String?, max: Int): String? {
        if (raw == null) return null
        val s = raw.replace(CONTROL, " ").replace(MARKUP, " ").replace(SPACES, " ").trim()
        return s.take(max).trim().ifEmpty { null }
    }

    /** URIs: control chars and whitespace make the URI invalid → null; no markup stripping (query syntax). */
    fun cleanUri(raw: String?, max: Int): String? {
        val u = raw?.trim() ?: return null
        if (u.isEmpty() || u.length > max) return null
        if (u.any { it.isWhitespace() || Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return null
        if (u.any { it in "{}<>\"`" }) return null
        return u
    }
}
