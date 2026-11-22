package io.androllm.feature.voice.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceReplyMatcherTest {

    @Test fun `russian yes and no`() {
        assertThat(VoiceReplyMatcher.matchYesNo("Да")).isTrue()
        assertThat(VoiceReplyMatcher.matchYesNo("Да, подтверждаю.")).isTrue()
        assertThat(VoiceReplyMatcher.matchYesNo("Давай")).isTrue()
        assertThat(VoiceReplyMatcher.matchYesNo("Нет")).isFalse()
        assertThat(VoiceReplyMatcher.matchYesNo("Не надо")).isFalse()
        assertThat(VoiceReplyMatcher.matchYesNo("Отмена!")).isFalse()
    }

    @Test fun `no wins over yes`() {
        assertThat(VoiceReplyMatcher.matchYesNo("да... нет, отмена")).isFalse()
        assertThat(VoiceReplyMatcher.matchYesNo("yes, no wait")).isFalse()
    }

    @Test fun `english and azerbaijani`() {
        assertThat(VoiceReplyMatcher.matchYesNo("Yes please")).isTrue()
        assertThat(VoiceReplyMatcher.matchYesNo("nope")).isFalse()
        assertThat(VoiceReplyMatcher.matchYesNo("Bəli")).isTrue()
        assertThat(VoiceReplyMatcher.matchYesNo("yox")).isFalse()
    }

    @Test fun `unknown or empty abstains`() {
        assertThat(VoiceReplyMatcher.matchYesNo("какая погода")).isNull()
        assertThat(VoiceReplyMatcher.matchYesNo("")).isNull()
        assertThat(VoiceReplyMatcher.matchYesNo(null)).isNull()
        // substring must not match: "данные" is not "да"
        assertThat(VoiceReplyMatcher.matchYesNo("данные")).isNull()
    }

    @Test fun `wake prefix stripped in latin and cyrillic`() {
        assertThat(VoiceReplyMatcher.stripWakePrefix("Hey Andro, what time is it?")).isEqualTo("what time is it?")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Эй, Андро, какая погода?")).isEqualTo("какая погода?")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Хей андро сколько времени")).isEqualTo("сколько времени")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Андроид, включи свет")).isEqualTo("включи свет")
        assertThat(VoiceReplyMatcher.stripWakePrefix("Какая погода?")).isEqualTo("Какая погода?")
    }
}
