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
