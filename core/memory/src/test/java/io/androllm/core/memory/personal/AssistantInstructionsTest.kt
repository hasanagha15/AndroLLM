package io.androllm.core.memory.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInstructionsTest {

    @Test
    fun defaultMentionsAllLanguagesTranslitAndReplyInUserLanguage() {
        val text = AssistantInstructions.build(PersonalContextSettings())
        assertTrue(text.contains("Russian"))
        assertTrue(text.contains("Azerbaijani"))
        assertTrue(text.contains("English"))
        assertTrue(text.contains("transliteration"))
        assertTrue(text.contains("Reply in the same language"))
        assertTrue(text.contains("\"ti kto\""))
        assertTrue(text.contains("not names"))
    }

    @Test
    fun paymentRuleOffersToOpenTheApp() {
        val text = AssistantInstructions.build(PersonalContextSettings())
        assertTrue(text.contains("cannot make payments"))
        assertTrue(text.contains("launch_app"))
        assertTrue(text.contains("offer to"))
        assertTrue(text.contains("never tap, type or navigate inside"))
    }

    @Test
    fun emptyPersonalContextAddsNoUserSection() {
        val text = AssistantInstructions.build(PersonalContextSettings(text = "   "))
        assertFalse(text.contains(AssistantInstructions.PERSONAL_HEADER))
    }

    @Test
    fun personalContextIsInjected() {
        val text = AssistantInstructions.build(PersonalContextSettings(text = "Country: X. Currency: Y."))
        assertTrue(text.contains(AssistantInstructions.PERSONAL_HEADER))
        assertTrue(text.contains("Country: X. Currency: Y."))
    }

    @Test
    fun personalContextIsCappedEvenWhenUnlimited() {
        val long = "a".repeat(5000)
        val text = AssistantInstructions.build(PersonalContextSettings(text = long))
        val personal = text.substringAfter(AssistantInstructions.PERSONAL_HEADER + "\n")
        assertEquals(AssistantInstructions.PERSONAL_MAX_CHARS, personal.length)
    }

    @Test
    fun respectsSmallBudget() {
        val budget = 1000
        val text = AssistantInstructions.build(PersonalContextSettings(text = "b".repeat(900)), budget)
        assertTrue("len=${text.length}", text.length <= budget)
        assertTrue(text.contains("cannot make payments"))
    }

    @Test
    fun tinyBudgetDropsTranslitHintButKeepsEssentials() {
        val text = AssistantInstructions.build(PersonalContextSettings(text = "facts"), 500)
        assertFalse(text.contains("\"ti kto\""))
        assertTrue(text.contains("Reply in the same language"))
        assertTrue(text.contains("cannot make payments"))
    }

    @Test
    fun fixedReplyLanguage() {
        val text = AssistantInstructions.build(PersonalContextSettings(replyLanguage = ReplyLanguage.RUSSIAN))
        assertTrue(text.contains("Always reply in Russian"))
        assertFalse(text.contains("Reply in the same language"))
    }

    @Test
    fun replyLanguageCodesRoundTrip() {
        ReplyLanguage.entries.forEach { assertEquals(it, ReplyLanguage.fromCode(it.code)) }
        assertEquals(ReplyLanguage.AUTO, ReplyLanguage.fromCode(null))
        assertEquals(ReplyLanguage.AUTO, ReplyLanguage.fromCode("xx"))
    }
}
