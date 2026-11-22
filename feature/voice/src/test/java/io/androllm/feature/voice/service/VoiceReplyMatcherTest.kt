package io.androllm.feature.voice.service

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Phrase table from the Fable re-gate (reviews/A-regate-fable.md, matcher.log).
 * Target: zero false approvals.
 */
class VoiceReplyMatcherTest {

    private fun m(s: String?) = VoiceReplyMatcher.matchYesNo(s)

    private val clearYes = listOf(
        "Да.", "Да, подтверждаю.", "Давай.", "Да, отправляй.", "Yes.", "Yes, please.", "Okay.", "Bəli.", "yes",
        "Да, давай.", "Да-да.", "Да, конечно.", "Конечно, отправляй.", "Да, пожалуйста.", "Yes, do it.",
        "Sure, go ahead.", "Hə, olar."
    )

    private val clearNo = listOf("Нет.", "Не надо.", "Отмена.", "No.", "Yox.", "да... нет, отмена")

    /** Wait / stop / doubt containing a yes-word: must never approve. */
    private val holdWithYesWord = listOf(
        "Да стой ты!", "Да подожди.", "Да погоди ты.", "Давай потом.", "Хорошо, но позже.", "Ок, подожди.",
        "Да ну.", "Да что ты делаешь?", "Да кому ты звонишь?", "Okay, wait.", "I'm not sure.", "Not okay.",
        "That's not ok.", "Sure... actually, hold on.", "Yes... wait.", "Please don't.", "Please don’t.",
        "Yaxşı deyil.", "Yaxşı, göndərmə.", "Olmaz."
    )

    private val holdAbstain = listOf("Да ну.", "Да что ты делаешь?", "Да кому ты звонишь?")

    /** Of the above, these carry an explicit stop/negation word -> refuse. */
    private val holdRefused = holdWithYesWord - holdAbstain.toSet()

    private val holdWithoutYesWord = listOf("Стой!", "Подожди.", "Потом.", "Wait.", "Hold on.")

    private val noiseNonYes = listOf(
        "Thank you.", "Thanks for watching!", "you", "Продолжение следует...", "Субтитры сделал DimaTorzok", "Угу."
    )

    /** Real agreement words that whisper also produces on silence. */
    private val noiseYesWords = listOf("Okay.", "Yeah.", "Хорошо.", "Ага.")

    @Test fun `clear yes approves`() {
        clearYes.forEach { assertWithMessage(it).that(m(it)).isTrue() }
    }

    @Test fun `ну да abstains rather than approves`() {
        assertThat(m("Ну да.")).isNull()
    }

    @Test fun `clear no refuses`() {
        clearNo.forEach { assertWithMessage(it).that(m(it)).isFalse() }
    }

    @Test fun `hold or doubt with a yes word never approves`() {
        holdWithYesWord.forEach { assertWithMessage(it).that(m(it)).isNotEqualTo(true) }
        holdRefused.forEach { assertWithMessage(it).that(m(it)).isFalse() }
        holdAbstain.forEach { assertWithMessage(it).that(m(it)).isNull() }
    }

    @Test fun `hold without a yes word refuses`() {
        holdWithoutYesWord.forEach { assertWithMessage(it).that(m(it)).isFalse() }
    }

    @Test fun `noise and unrelated phrases abstain`() {
        (noiseNonYes + listOf("Какая погода?", "данные", "", "   ")).forEach {
            assertWithMessage(it).that(m(it)).isNull()
        }
        assertThat(m(null)).isNull()
    }

    @Test fun `echo of the question refuses`() {
        assertThat(m("Do you want me to send the SMS to John? Say yes to confirm, or no to cancel.")).isFalse()
    }

    @Test fun `long replies with yes words abstain`() {
        assertThat(m("да да да хорошо давай конечно ок")).isNull()
    }

    @Test fun `noise yes words are never decoded when no speech was detected`() {
        // The matcher alone accepts these real agreement words; the listening
        // loop never decodes a no-speech recording, and a confirmation reply
        // requires detected speech, so they cannot reach matchYesNo from silence.
        noiseYesWords.forEach { assertWithMessage(it).that(m(it)).isTrue() }
        assertThat(VoiceTurnPolicy.shouldDecode("no-speech", sawSpeech = false, requireSpeech = true)).isFalse()
        assertThat(VoiceTurnPolicy.shouldDecode("no-speech", sawSpeech = false, requireSpeech = false)).isFalse()
        assertThat(VoiceTurnPolicy.shouldDecode("max-duration", sawSpeech = false, requireSpeech = true)).isFalse()
        assertThat(VoiceTurnPolicy.shouldDecode("endpoint", sawSpeech = true, requireSpeech = true)).isTrue()
        assertThat(VoiceTurnPolicy.shouldDecode("max-duration", sawSpeech = false, requireSpeech = false)).isTrue()
    }

    @Test fun `zero false approvals over the whole table`() {
        val mustNotApprove = clearNo + holdWithYesWord + holdWithoutYesWord + noiseNonYes +
            listOf(
                "Ну да.", "Какая погода?", "данные", "",
                "Do you want me to send the SMS to John? Say yes to confirm, or no to cancel."
            )
        assertThat(mustNotApprove.filter { m(it) == true }).isEmpty()
    }

    // ── wake-word residue ────────────────────────────────────────────────────

    @Test fun `wake phrases are stripped`() {
        assertThat(VoiceReplyMatcher.stripWakePrefix("Hey Andro, what time is it?")).isEqualTo("what time is it?")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Эй, Андро, какая погода?")).isEqualTo("какая погода?")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Андро. Какая погода?")).isEqualTo("Какая погода?")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Хей, Андройд! Включи свет.")).isEqualTo("Включи свет.")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Хей андро сколько времени")).isEqualTo("сколько времени")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Okay Andro, call mom")).isEqualTo("call mom")
    }

    @Test fun `names and ordinary words are not stripped`() {
        listOf(
            "Андрюше позвони.", "Андрюхе напиши, что буду поздно.", "Андрею позвони.", "Andrew called?",
            "Android Auto, start navigation.", "Какая погода?", "Эй, какая погода?", "Андроид, включи свет"
        ).forEach { assertWithMessage(it).that(VoiceReplyMatcher.stripWakePrefix(it)).isEqualTo(it) }
    }
}
