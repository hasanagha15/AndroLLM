package io.androllm.core.memory.personal

/** Which language the assistant answers in. */
enum class ReplyLanguage(val code: String, val label: String) {
    /** Answer in the language the user wrote in (default). */
    AUTO("auto", "Same as user"),
    RUSSIAN("ru", "Русский"),
    AZERBAIJANI("az", "Azərbaycan"),
    ENGLISH("en", "English");

    companion object {
        fun fromCode(code: String?): ReplyLanguage =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * User-entered context, stored only on this device ([PersonalContextStore]).
 * Ships empty: the public fork never contains anyone's personal data.
 */
data class PersonalContextSettings(
    val text: String = "",
    val replyLanguage: ReplyLanguage = ReplyLanguage.AUTO
) {
    companion object {
        /** Max characters the user can store. */
        const val MAX_TEXT_CHARS = 1000
    }
}

/**
 * Base system instructions injected FIRST into every chat turn (local and
 * cloud, text chat and voice) — AndroLLM fork F1:
 *
 *  1. Language: the user may write Russian, Azerbaijani, English, or Russian /
 *     Azerbaijani in Latin transliteration; reply in the user's language (or
 *     the fixed [ReplyLanguage]). A small, generic transliteration hint stops
 *     small models from reading "ti kto" as a name ("I'm Ti Kyo").
 *  2. Payments: the agent can never pay or transfer money (no such tool);
 *     instead of a bare refusal it offers to open the relevant app with
 *     `launch_app` (open only — no further automation).
 *  3. Personal context: what the user typed in Settings, budget-capped.
 *
 * Pure and unit-tested; callers pass a character budget derived from the
 * context window so the block never crowds out history or the tool list.
 */
object AssistantInstructions {

    /** Upper bound for the personal-context part regardless of window size. */
    const val PERSONAL_MAX_CHARS = 600

    /** Below this budget only the essential lines are emitted. */
    const val MIN_FULL_BUDGET_CHARS = 900

    internal const val LANGUAGE_AUTO =
        "Language: the user may write in Russian, Azerbaijani or English, or type Russian or " +
            "Azerbaijani with Latin letters (transliteration). Reply in the same language the user " +
            "wrote in; for transliterated Russian reply in Russian (Cyrillic), for transliterated " +
            "Azerbaijani reply in Azerbaijani."

    internal const val TRANSLIT_HINT =
        "Transliteration examples: \"ti kto\" = \"ты кто\" (who are you), \"privet\" = \"привет\", " +
            "\"spasibo\" = \"спасибо\", \"salam, necesen\" = \"salam, necəsən\" (hi, how are you). " +
            "Transliterated words are ordinary words, not names."

    internal const val PAYMENTS =
        "Payments: you cannot make payments, money transfers or purchases, and no tool can. If the " +
            "user asks to pay a bill or send money, say briefly that you cannot pay, and offer to " +
            "open the relevant bank, wallet or utility app (launch_app) so the user can pay there " +
            "themselves. Do not claim you cannot help at all."

    internal const val PERSONAL_HEADER =
        "About the user (written by the user, stored on this device; use it when relevant):"

    /**
     * Builds the instruction block. [maxChars] <= 0 means unlimited (cloud).
     * Returns a non-blank string (language + payments are always present).
     */
    fun build(settings: PersonalContextSettings, maxChars: Int = 0): String {
        val unlimited = maxChars <= 0
        val sb = StringBuilder()
        sb.append(languageLine(settings.replyLanguage))
        if (unlimited || maxChars >= MIN_FULL_BUDGET_CHARS) {
            sb.append('\n').append(TRANSLIT_HINT)
        }
        sb.append('\n').append(PAYMENTS)

        val personal = settings.text.trim()
        if (personal.isNotEmpty()) {
            val room = if (unlimited) {
                PERSONAL_MAX_CHARS
            } else {
                minOf(PERSONAL_MAX_CHARS, maxChars - sb.length - PERSONAL_HEADER.length - 2)
            }
            if (room >= MIN_PERSONAL_CHARS) {
                sb.append('\n').append(PERSONAL_HEADER).append('\n').append(truncate(personal, room))
            }
        }
        return sb.toString()
    }

    internal fun languageLine(lang: ReplyLanguage): String = when (lang) {
        ReplyLanguage.AUTO -> LANGUAGE_AUTO
        ReplyLanguage.RUSSIAN -> fixed("Russian (Cyrillic)")
        ReplyLanguage.AZERBAIJANI -> fixed("Azerbaijani")
        ReplyLanguage.ENGLISH -> fixed("English")
    }

    private fun fixed(name: String): String =
        "Language: the user may write in Russian, Azerbaijani or English, or type Russian or " +
            "Azerbaijani with Latin letters (transliteration). Always reply in $name."

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private const val MIN_PERSONAL_CHARS = 40
}
