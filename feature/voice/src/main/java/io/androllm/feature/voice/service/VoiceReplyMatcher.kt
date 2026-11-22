package io.androllm.feature.voice.service

/**
 * Language-aware parsing of short spoken replies (en / ru / az) and wake-word
 * residue removal. Pure, unit-tested.
 */
object VoiceReplyMatcher {

    private val NO_WORDS = listOf(
        // en
        "no", "nope", "cancel", "don't", "dont", "stop", "never", "forget it", "no thanks",
        // ru
        "нет", "не надо", "не нужно", "отмена", "отменить", "отмени", "стоп", "не", "никогда",
        // az
        "yox", "xeyr", "ləğv et", "lazım deyil", "dayan"
    )

    private val YES_WORDS = listOf(
        // en
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "confirm", "send it", "please",
        // ru
        "да", "ага", "давай", "подтверждаю", "подтвердить", "конечно", "хорошо", "окей", "ок", "отправляй",
        "делай", "согласен", "согласна",
        // az
        "bəli", "hə", "əlbəttə", "təsdiq edirəm", "olar", "yaxşı"
    )

    private val SEPARATORS = Regex("[\\s,.!?;:«»\"—–-]+")

    /**
     * Maps a spoken reply to yes (true) / no (false) / unknown (null = abstain,
     * the in-app card stays the decision surface). Any "no" phrase wins over
     * "yes" so "нет, не надо, да" is never consent.
     */
    fun matchYesNo(reply: String?): Boolean? {
        val norm = reply?.lowercase()?.replace(SEPARATORS, " ")?.trim().orEmpty()
        if (norm.isEmpty()) return null
        val padded = " $norm "
        if (NO_WORDS.any { padded.contains(" $it ") }) return false
        if (YES_WORDS.any { padded.contains(" $it ") }) return true
        return null
    }

    /** Leading wake word ("hey andro", "эй андро", …) at the start of a transcript. */
    val WAKE_PREFIX = Regex(
        """^\s*(?iu:(?:(?:hey|hai|hei|hi|эй|хей|хэй|гей|хай)[,\s]+)?(?:andro\p{L}*|andrew\p{L}*|android\p{L}*|андро\p{L}*|андрю\p{L}*|эндрю\p{L}*|андройд\p{L}*))[,.!?\s]*"""
    )

    fun stripWakePrefix(text: String): String {
        val m = WAKE_PREFIX.find(text) ?: return text
        return text.substring(m.range.last + 1).trim()
    }
}
