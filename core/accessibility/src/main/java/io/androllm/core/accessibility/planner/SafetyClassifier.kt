package io.androllm.core.accessibility.planner

import java.text.Normalizer

/**
 * Pure keyword classifier that decides whether an automation step is
 * high-risk and must be confirmed first. Mirrors the confirmation rules of the
 * tool framework: sending, calling, paying, booking, deleting, installing,
 * granting, signing, account or system changes always require explicit
 * approval — everything else executes immediately.
 *
 * Branch M: multilingual (en / ru / az, plus Turkish forms). Text is FOLDED
 * before matching — NFKD with combining marks removed (ö→o, ş→s, ə/ә→e,
 * İ/ı→i, й→и), zero-width / bidi / soft-hyphen characters dropped — and
 * checked in three homoglyph variants (as is, Cyrillic look-alikes → Latin,
 * Latin look-alikes → Cyrillic), so "Göndәr" (Cyrillic ә), "Gönder", "Sеnd"
 * (Cyrillic е) and "Oтправить" (Latin O) all match. The same predicate drives
 * the ui_click / ui_type / ui_gesture tap-only card, the resolved-element gate
 * in the controller ([io.androllm.core.accessibility.controller.RiskyElementGate]),
 * the ui_run step confirmation and the App Atlas learned-path cut-off.
 */
object SafetyClassifier {

    private val RAW_GOAL_WORDS = listOf(
        "send", "pay", "payment", "buy", "purchase", "book", "booking", "order",
        "place order", "confirm", "delete", "remove", "install", "uninstall",
        "transfer", "subscribe", "subscription", "charge", "checkout",
        "withdraw", "change password", "reset", "cancel subscription", "sign up",
        "call", "dial", "post", "publish", "share", "forward", "allow", "grant", "accept", "agree", "sign",
        // ru
        "отправ", "послать", "оплат", "заплат", "купи", "покуп", "закаж", "заказ", "брониров", "заброниру",
        "подтверд", "удали", "удален", "установи", "перевед", "перевест", "подпиш", "подписк", "подписа",
        "позвон", "звон", "вызов", "пересла", "поделит", "опубликов", "разреш", "принять", "соглас",
        // az / tr
        "göndər", "gönder", "yolla", "ödə", "ödeme", "satın al", "sifariş", "sipariş", "rezerv", "təsdiq", "onayla",
        "sil", "köçür", "quraşdır", "abunə", "abone", "zəng", "paylaş", "icazə", "izin ver", "qəbul", "kabul", "imzala"
    )

    /** Substring matches (an element label CONTAINS one of these, after folding). */
    private val RAW_BUTTON_WORDS = listOf(
        // en
        "send", "pay", "buy", "purchase", "book", "order", "place order",
        "confirm", "checkout", "delete", "remove", "install", "transfer",
        "subscribe", "charge", "withdraw", "submit", "request", "reserve",
        "place", "publish", "tweet", "retweet", "repost", "share", "forward",
        "allow", "grant", "accept", "agree", "approve", "authoriz", "authoris",
        "call now", "make call", "voice call", "video call",
        // ru (stems: отправить/отправка, оплатить/оплата, заказать/заказ…)
        "отправ", "послать", "оплат", "заплат", "купить", "покупк", "заказ", "заброниров", "бронир",
        "подтверд", "удалить", "удален", "установить", "перевести", "перевод средств", "перевод денег",
        "подписаться", "подписа", "оформить", "списать", "опубликов", "пересла", "поделиться",
        "позвон", "звонок", "вызов", "набрать номер", "разреш", "принять", "соглас",
        // az + tr (göndər/gönder, yolla, ödəniş/ödeme, satın al, sifariş/sipariş, təsdiqlə/onayla,
        // köçür, quraşdır, abunə ol/abone ol, paylaş, zəng et, icazə ver/izin ver, qəbul et/kabul et, imzala)
        "göndər", "gönder", "yolla", "ödəniş", "ödeme", "satın al", "sifariş", "sipariş",
        "təsdiq", "onayla", "köçür", "quraşdır", "abunə", "abone ol", "rezerv", "bron et",
        "dərc et", "paylaş", "zəng", "icazə", "izin ver", "qəbul et", "kabul et", "razıyam", "imzala"
    )

    /**
     * Short / ambiguous words matched only as a WHOLE word after folding
     * ("sil" ≠ "silindr", "al" ≠ "albom", "call" ≠ "recall", "sign" ≠ "design").
     */
    private val RAW_WHOLE_WORDS = setOf("sil", "al", "ödə", "öde", "call", "dial", "post", "sign", "пошли")

    /** Words marking a swipe / slide-to-confirm control. */
    private val RAW_SLIDE_WORDS = listOf("slide", "swipe", "сдвин", "провед", "смахн", "sürüşdür", "kaydır")

    /** $ € £ ₼ ₽ ₺ ¥ */
    private val CURRENCY_SYMBOLS: Set<Char> =
        intArrayOf(0x24, 0x20AC, 0xA3, 0x20BC, 0x20BD, 0x20BA, 0xA5).map { it.toChar() }.toSet()
    private val CURRENCY_WORDS = setOf("azn", "usd", "eur", "rub", "try", "gbp", "tl", "manat", "man", "руб", "р")

    private val GOAL_WORDS: List<String> by lazy { RAW_GOAL_WORDS.map(::fold).distinct() }
    private val BUTTON_WORDS: List<String> by lazy { RAW_BUTTON_WORDS.map(::fold).distinct() }
    private val WHOLE_WORDS: Set<String> by lazy { RAW_WHOLE_WORDS.map(::fold).toSet() }
    private val SLIDE_WORDS: List<String> by lazy { RAW_SLIDE_WORDS.map(::fold) }

    /** Cyrillic look-alikes а е о р с у х к м т в н і ѕ ј and their Latin twins. */
    private val CYR: String = intArrayOf(
        0x430, 0x435, 0x43E, 0x440, 0x441, 0x443, 0x445, 0x43A, 0x43C, 0x442, 0x432, 0x43D, 0x456, 0x455, 0x458
    ).map { it.toChar() }.joinToString("")
    private const val LAT = "aeopcyxkmtbhisj"

    // ── folding ─────────────────────────────────────────────────────────────

    private fun isInvisible(code: Int): Boolean =
        code in 0x200B..0x200F || code == 0x2060 || code == 0xFEFF || code == 0xAD || code in 0x202A..0x202E

    /**
     * Lower-case, accent-free form: NFKD, combining marks dropped, ə/ә → e,
     * ı → i, zero-width / bidi / soft-hyphen characters removed, underscores of
     * view ids → spaces (`send_button` → `send button`).
     */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val code = ch.code
            if (isInvisible(code)) continue
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            when (code) {
                0x0259, 0x018F, 0x04D9, 0x04D8 -> sb.append('e') // ə Ə ә Ә
                0x0131 -> sb.append('i') // ı
                0x5F -> sb.append(' ') // _
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase().trim()
    }

    /** Same as [fold] (earlier API name). */
    fun normalize(text: String): String = fold(text)

    private fun mapChars(s: String, from: String, to: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val i = from.indexOf(c)
            sb.append(if (i >= 0) to[i] else c)
        }
        return sb.toString()
    }

    /** The folded text plus its two homoglyph-normalised variants. */
    private fun variants(text: String): List<String> {
        val f = fold(text)
        return listOf(f, mapChars(f, CYR, LAT), mapChars(f, LAT, CYR)).distinct()
    }

    private fun words(t: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in t) {
            if (c.isLetterOrDigit()) {
                sb.append(c)
            } else {
                if (sb.isNotEmpty()) out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun matchesAny(text: String, substrings: List<String>): Boolean =
        variants(text).any { v ->
            substrings.any { v.contains(it) } || words(v).any { it in WHOLE_WORDS }
        }

    // ── predicates ──────────────────────────────────────────────────────────

    fun isRiskyGoal(goal: String): Boolean =
        variants(goal).any { v ->
            val ws = words(v).toSet()
            GOAL_WORDS.any { w -> if (w in WHOLE_WORDS) w in ws else v.contains(w) }
        }

    /**
     * True when an element label (text, content description or view id)
     * suggests a risky action. No length limit (branch M gate B1): a long
     * content description ("Send message to …") is checked in full.
     */
    fun isRiskyTarget(target: String): Boolean {
        if (target.isBlank()) return false
        return matchesAny(target, BUTTON_WORDS) || isAmountOnly(fold(target))
    }

    /**
     * A button whose label is only an amount with a currency ("₼ 25,00",
     * "AZN 20", "$4.99", "25 ₽"): tapping it pays.
     */
    fun isAmountOnly(folded: String): Boolean {
        var digit = false
        var symbol = false
        val letters = StringBuilder()
        for (c in folded) {
            when {
                c.isDigit() -> { digit = true; letters.append(' ') }
                c in CURRENCY_SYMBOLS -> { symbol = true; letters.append(' ') }
                c.isLetter() -> letters.append(c)
                c.isWhitespace() || c in ".,'-+" -> letters.append(' ')
                else -> return false
            }
        }
        val ws = letters.split(' ').filter { it.isNotEmpty() }
        if (ws.any { it !in CURRENCY_WORDS }) return false
        return digit && (symbol || ws.isNotEmpty())
    }

    /** A risky "slide / swipe to pay / confirm" control. */
    fun isSlideToConfirm(label: String): Boolean =
        isRiskyTarget(label) && variants(label).any { v -> SLIDE_WORDS.any { v.contains(it) } }

    /**
     * Confirmation decision for an emitted [action]: risky buttons always
     * confirm; a risky goal + any "send/confirm"-labelled tap confirms too.
     */
    fun requiresConfirmation(goal: String, action: PlannedAction): Boolean {
        if (action !is PlannedAction.Click) return false
        if (isRiskyTarget(action.target)) return true
        // Risky goal + the tapped button is part of that risky flow ("Book an
        // Uber" → tapping "Request ride") → confirm.
        return isRiskyGoal(goal) && matchesAny(action.target, BUTTON_WORDS)
    }

    /**
     * Branch M: typing text that contains a line break can submit a message in
     * chat apps ("Enter sends"); such a ui_type is treated like tapping Send.
     */
    fun isSubmittingText(text: String): Boolean = text.any { it == '\n' || it == '\r' }
}
