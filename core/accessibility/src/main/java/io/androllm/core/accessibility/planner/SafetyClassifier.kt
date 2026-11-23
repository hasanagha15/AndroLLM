package io.androllm.core.accessibility.planner

/**
 * Pure keyword classifier that decides whether an automation step is
 * high-risk and must be confirmed first. Mirrors the confirmation rules of the
 * tool framework: sending, paying, booking, deleting, installing, account or
 * system changes always require explicit approval — everything else executes
 * immediately.
 *
 * Branch M: multilingual (en / ru / az). The same predicate drives the
 * ui_click / ui_type / ui_gesture tap-only confirmation card, the ui_run step
 * confirmation and the App Atlas learned-path cut-off (LearnedPathPolicy), so
 * they can never disagree about what a "risky" element is.
 */
object SafetyClassifier {

    private val RISKY_GOAL_WORDS = listOf(
        "send", "pay", "payment", "buy", "purchase", "book", "booking", "order",
        "place order", "confirm", "delete", "remove", "install", "uninstall",
        "transfer", "subscribe", "subscription", "charge", "checkout",
        "withdraw", "change password", "reset", "cancel subscription", "sign up",
        // ru
        "отправ", "оплат", "заплат", "купи", "покуп", "закаж", "заказ", "брониров", "заброниру",
        "подтверд", "удали", "удален", "установи", "перевед", "перевест", "подпиш", "подписк",
        // az
        "göndər", "gonder", "ödə", "satın al", "satin al", "sifariş", "sifaris", "rezerv", "təsdiq", "tesdiq",
        "sil", "köçür", "kocur", "quraşdır", "qurasdir", "abunə", "abune"
    )

    /** Substring matches (a button label CONTAINS one of these). */
    private val RISKY_BUTTON_WORDS = listOf(
        "send", "pay", "buy", "purchase", "book", "order", "place order",
        "confirm", "checkout", "delete", "remove", "install", "transfer",
        "subscribe", "charge", "withdraw", "submit", "request", "reserve",
        "place", "publish",
        // ru (stems: отправить/отправка, оплатить/оплата, заказать/заказ…)
        "отправ", "оплат", "заплат", "купить", "покупк", "заказ", "заброниров", "бронир",
        "подтверд", "удалить", "удален", "установить", "перевести", "перевод средств", "перевод денег",
        "подписаться", "оформить", "списать", "опубликова",
        // az (göndər, ödə / ödəniş, satın al, sifariş, təsdiqlə, köçür, quraşdır, abunə ol)
        "göndər", "gonder", "ödə", "ödəniş", "odenis", "satın al", "satin al", "sifariş", "sifaris",
        "təsdiq", "tesdiq", "köçür", "kocur", "quraşdır", "qurasdir", "abunə", "abune", "rezerv", "bron et",
        "dərc et"
    )

    /** Short words matched only as a WHOLE word ("sil" ≠ "silindr", "al" ≠ "albom"). */
    private val RISKY_WHOLE_WORDS = setOf("sil", "al", "ode")

    private const val COMBINING_DOT_ABOVE = 0x0307

    /**
     * Lower-case form that also survives Turkic / Azerbaijani casing: `İ`
     * lower-cases to `i` + combining dot above under the root locale, so the
     * dot is dropped, and dotless `ı` maps to `i` (`TƏSDİQ` → `təsdiq`).
     * Underscores of view ids become spaces (`send_button` → `send button`).
     */
    fun normalize(text: String): String =
        text.lowercase()
            .filter { it.code != COMBINING_DOT_ABOVE }
            .replace('ı', 'i')
            .replace('_', ' ')
            .trim()

    private fun words(t: String): List<String> =
        t.splitBy { !it.isLetterOrDigit() }.filter { it.isNotEmpty() }

    private fun String.splitBy(isSeparator: (Char) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                out += sb.toString(); sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    fun isRiskyGoal(goal: String): Boolean {
        val g = normalize(goal)
        val ws = words(g).toSet()
        return RISKY_GOAL_WORDS.any { w -> if (w in RISKY_WHOLE_WORDS) w in ws else g.contains(w) }
    }

    /** True when the tapped element's label (or view id) suggests a risky action. */
    fun isRiskyTarget(target: String): Boolean {
        val t = normalize(target)
        if (t.isEmpty() || t.length > 48) return false
        if (RISKY_BUTTON_WORDS.any { t == it || t.contains(it) }) return true
        return words(t).any { it in RISKY_WHOLE_WORDS }
    }

    /**
     * Confirmation decision for an emitted [action]: risky buttons always
     * confirm; a risky goal + any "send/confirm"-labelled tap confirms too.
     */
    fun requiresConfirmation(goal: String, action: PlannedAction): Boolean {
        if (action !is PlannedAction.Click) return false
        if (isRiskyTarget(action.target)) return true
        // Risky goal + the tapped button is part of that risky flow ("Book an
        // Uber" → tapping "Request ride") → confirm.
        val t = normalize(action.target)
        return isRiskyGoal(goal) && RISKY_BUTTON_WORDS.any { t.contains(it) }
    }

    /**
     * Branch M: typing text that contains a line break can submit a message in
     * chat apps ("Enter sends"); such a ui_type is treated like tapping Send.
     */
    fun isSubmittingText(text: String): Boolean = text.any { it == '\n' || it == '\r' }
}
