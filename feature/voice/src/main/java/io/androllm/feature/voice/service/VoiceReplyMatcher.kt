package io.androllm.feature.voice.service

/**
 * Language-aware parsing of short spoken confirmation replies (en / ru / az)
 * and wake-word residue removal. Pure, unit-tested.
 *
 * Safety rule (voice confirmation gates calls, SMS, e-mail, automation):
 *  - any stop / negation / "wait" / "later" word ANYWHERE -> refuse (false);
 *  - approve (true) ONLY when a short reply consists entirely of agreement
 *    words (plus the explicit polite fillers in [FILLERS]);
 *  - everything else -> abstain (null): the in-app card stays the decision
 *    surface and nothing is executed without an explicit tap.
 * In Russian «да» is often a particle («Да подожди», «Да кому ты звонишь?»),
 * so a single yes-word inside a longer phrase is never consent.
 */
object VoiceReplyMatcher {

    /** Refusal / hold words and phrases (whole-token match anywhere in the reply). */
    private val NO_OR_HOLD = listOf(
        // en
        "no", "not", "nope", "nah", "don't", "dont", "do not", "cancel", "stop", "wait", "hold on", "hang on",
        "later", "never", "forget it", "abort", "nothing",
        // ru
        "нет", "не", "ни", "неа", "отмена", "отмени", "отменить", "отменяй", "стоп", "стой", "постой",
        "подожди", "погоди", "подождите", "погодите", "потом", "позже", "хватит", "нельзя", "никогда",
        "не надо", "не нужно", "передумал", "передумала",
        // az
        "yox", "xeyr", "olmaz", "deyil", "gözlə", "gözləyin", "sonra", "dayan", "ləğv", "göndərmə", "etmə",
        "lazım deyil"
    )

    /** Agreement words/phrases. Multi-word entries are consumed first. */
    private val YES = listOf(
        // en
        "go ahead", "do it", "send it", "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed",
        // ru
        "да", "ага", "давай", "подтверждаю", "подтвердить", "конечно", "хорошо", "окей", "ок", "отправляй",
        "отправь", "звони", "делай", "согласен", "согласна", "верно",
        // az
        "təsdiq edirəm", "bəli", "hə", "əlbəttə", "olar", "yaxşı", "razıyam"
    ).sortedByDescending { it.length }

    /** Words allowed around agreement words without changing the meaning. */
    private val FILLERS = listOf("please", "пожалуйста", "zəhmət olmasa")

    /** Longer replies are never treated as a plain "yes". */
    private const val MAX_TOKENS = 6

    private val SEPARATORS = Regex("[\\s,.!?;:«»\"“”„—–\\-…()]+")

    fun normalize(reply: String): String =
        reply.lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
            .replace(SEPARATORS, " ")
            .trim()

    /**
     * Maps a spoken reply to yes (true) / no (false) / unknown (null = abstain).
     */
    fun matchYesNo(reply: String?): Boolean? {
        val norm = reply?.let(::normalize).orEmpty()
        if (norm.isEmpty()) return null
        val padded = " $norm "
        if (NO_OR_HOLD.any { padded.contains(" $it ") }) return false
        if (norm.split(' ').size > MAX_TOKENS) return null
        var rest = padded
        var sawYes = false
        for (w in YES) {
            while (rest.contains(" $w ")) {
                rest = rest.replace(" $w ", " ")
                sawYes = true
            }
        }
        for (f in FILLERS) {
            while (rest.contains(" $f ")) rest = rest.replace(" $f ", " ")
        }
        return if (sawYes && rest.isBlank()) true else null
    }

    /**
     * Leading wake phrase at the start of a transcript. Only the actual wake
     * phrases are stripped: "andro"/"андро" (optionally after hey/эй/хей), and
     * "android"/"andrew"/"эндрю"/"андрю" ONLY after an explicit hey/эй.
     * Names like «Андрюше», «Андрею», "Andrew called?" are left intact.
     */
    val WAKE_PREFIX = Regex(
        """^\s*(?iu:(?:(?:hey|hai|hei|hi|okay|ok|эй|хей|хэй|хай|окей|ок)[,\s]+(?:andro|andrew|android|андро|андроид|андройд|эндрю|андрю))|(?:andro|андро))(?=[,.!?\s]|$)[,.!?\s]*"""
    )

    fun stripWakePrefix(text: String): String {
        val m = WAKE_PREFIX.find(text) ?: return text
        return text.substring(m.range.last + 1).trim()
    }
}

/** Pure decisions of the listening loop (unit-tested). */
object VoiceTurnPolicy {
    /**
     * Whether a recorded utterance may be decoded at all. A recording that
     * ended with "no-speech" is never decoded (whisper invents "Okay." /
     * «Ага.» on silence). For a confirmation reply ([requireSpeech]) speech
     * must have actually been detected.
     */
    fun shouldDecode(stopReason: String, sawSpeech: Boolean, requireSpeech: Boolean): Boolean =
        stopReason != "no-speech" && (!requireSpeech || sawSpeech)
}
