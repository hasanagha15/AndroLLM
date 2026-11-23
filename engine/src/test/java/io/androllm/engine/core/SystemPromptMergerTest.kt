package io.androllm.engine.core

import io.androllm.engine.models.ChatPromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemPromptMergerTest {

    @Test
    fun allSystemMessagesAreKeptInOrder() {
        val merged = SystemPromptMerger.merge(
            listOf(
                ChatPromptMessage("system", "instructions"),
                ChatPromptMessage("system", "memory"),
                ChatPromptMessage("user", "hi"),
                ChatPromptMessage("system", "tools")
            )
        )
        assertEquals("instructions\n\nmemory\n\ntools", merged)
    }

    @Test
    fun blankSystemMessagesAreSkipped() {
        val merged = SystemPromptMerger.merge(
            listOf(ChatPromptMessage("system", "  "), ChatPromptMessage("system", " tools "))
        )
        assertEquals("tools", merged)
    }

    @Test
    fun noSystemMessageGivesNull() {
        assertNull(SystemPromptMerger.merge(listOf(ChatPromptMessage("user", "hi"))))
    }
}
