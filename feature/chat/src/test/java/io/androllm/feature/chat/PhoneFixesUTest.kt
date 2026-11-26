package io.androllm.feature.chat

import io.androllm.core.models.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branch U (step 5): output reserve, history floor and stale "Running…" cards. */
class PhoneFixesUTest {

    private fun msg(role: MessageRole, text: String, i: Int) = ChatMessage("m$i", "c", role, text, i.toLong())

    @Test
    fun `output reserve is clamped to the context and to a third of it`() {
        // maxTokens 8192 with nCtx 4096 after the auto-reload.
        assertEquals(1365, localOutputReserve(8192, 4096))
        assertEquals(682, localOutputReserve(65536, 2048))
        assertEquals(512, localOutputReserve(512, 8192))
        assertEquals(256, localOutputReserve(100, 8192))
        assertTrue(localOutputReserve(8192, 4096) < 4096)
    }

    @Test
    fun `history floor covers the last exchange plus the prompt`() {
        val history = listOf(
            msg(MessageRole.USER, "выключи блютуз", 1),
            msg(MessageRole.ASSISTANT, "Я могу выключить Bluetooth. Выполнить действие?", 2),
            msg(MessageRole.USER, "да", 3)
        )
        val expected = history.sumOf { ChatHistoryTrimmer.estimateTokens(it.content) }.coerceAtLeast(128)
        assertEquals(expected, historyFloorTokens(history, 4096))
        // A huge exchange is capped at a quarter of the context.
        val huge = listOf(msg(MessageRole.USER, "x".repeat(40_000), 1), msg(MessageRole.ASSISTANT, "y", 2), msg(MessageRole.USER, "z", 3))
        assertEquals(1024, historyFloorTokens(huge, 4096))
        assertEquals(128, historyFloorTokens(emptyList(), 4096))
    }

    @Test
    fun `running tool cards are closed at turn end, finished ones untouched`() {
        val cards = listOf(
            ToolInvocationUi("set_flashlight", status = ToolInvocationStatus.RUNNING),
            ToolInvocationUi("get_battery", status = ToolInvocationStatus.SUCCESS, summary = "80%"),
            ToolInvocationUi("send_sms", status = ToolInvocationStatus.DECLINED)
        )
        val closed = closeRunningToolCards(cards, "Stopped")
        assertTrue(closed.none { it.status == ToolInvocationStatus.RUNNING })
        assertEquals(ToolInvocationStatus.FAILED, closed[0].status)
        assertEquals("Stopped", closed[0].error)
        assertEquals(cards[1], closed[1])
        assertEquals(cards[2], closed[2])
    }

    @Test
    fun `system budget is never zero - zero would mean unlimited`() {
        assertEquals(MIN_SYSTEM_BUDGET_CHARS, computeSystemCharsBudget(300, 256, 128))
        assertEquals((4096 - 1365 - 200) * 4, computeSystemCharsBudget(4096, 1365, 200))
    }
}
