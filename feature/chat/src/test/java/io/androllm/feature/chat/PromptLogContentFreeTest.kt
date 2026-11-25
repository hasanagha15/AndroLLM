package io.androllm.feature.chat

import io.androllm.engine.models.ChatPromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branch Q: the "PROMPT BUILT" log line carries roles and lengths only. */
class PromptLogContentFreeTest {

    private val messages = listOf(
        ChatPromptMessage(role = "system", content = "You are helpful"),
        ChatPromptMessage(role = "system", content = "[tool data] SMS from Q7Contact: Q7TOOLDATA"),
        ChatPromptMessage(role = "user", content = "Q7USERTEXT what did she say")
    )

    @Test
    fun `default prompt log has roles and lengths but no text`() {
        val line = describePromptForLog(messages, includeContent = false)
        assertEquals("system:15ch | system:42ch | user:27ch", line)
        for (marker in listOf("Q7Contact", "Q7TOOLDATA", "Q7USERTEXT", "helpful")) {
            assertFalse(marker, line.contains(marker))
        }
    }

    @Test
    fun `debug token logging adds the head of each message`() {
        val line = describePromptForLog(messages, includeContent = true)
        assertTrue(line.contains("Q7USERTEXT"))
    }
}
