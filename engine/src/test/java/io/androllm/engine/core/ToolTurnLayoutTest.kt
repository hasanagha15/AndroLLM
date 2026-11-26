package io.androllm.engine.core

import com.google.ai.edge.litertlm.Role
import io.androllm.engine.core.ChatTurnPlanner.DATA_CLOSE
import io.androllm.engine.core.ChatTurnPlanner.DATA_OPEN
import io.androllm.engine.models.ChatPromptMessage
import io.androllm.engine.models.ChatPromptToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch U (step 5): tool calls and results of the native tool loop are no
 * longer folded into the user turn for Gemma 4 (native tool-response role) and
 * follow the official Qwen3 template layout for Qwen.
 */
class ToolTurnLayoutTest {

    private fun sys(t: String) = ChatPromptMessage("system", t)
    private fun user(t: String) = ChatPromptMessage("user", t)
    private fun data(t: String) = "$DATA_OPEN\n$t\n$DATA_CLOSE"
    private val call = ChatPromptToolCall("get_device_info", "{}")
    private fun callTurn(text: String = "") = ChatPromptMessage("assistant", text, toolCalls = listOf(call))
    private fun result(t: String, note: String? = null) =
        ChatPromptMessage(ChatPromptMessage.ROLE_TOOL, t, toolName = "get_device_info", toolNote = note)

    private val round2 = listOf(sys("RULES"), user("turn off bluetooth"), callTurn(), result("Bluetooth: on"))

    @Test
    fun `gemma native - results are sent as tool responses after the tool-calling model turn`() {
        val plan = ChatTurnPlanner.plan(round2, layout = ToolTurnLayout.GEMMA_NATIVE)
        assertEquals("RULES", plan.systemInstruction)
        assertEquals(2, plan.seed.size)
        assertEquals("user", plan.seed[0].role)
        assertEquals(listOf(call), plan.seed[1].toolCalls)
        assertEquals(1, plan.promptTools.size)
        val tool = plan.promptTools.single()
        assertEquals(ChatPromptMessage.ROLE_TOOL, tool.role)
        assertEquals("get_device_info", tool.toolName)
        // Untrusted framing is kept inside the tool response; nothing went to the user turn.
        assertEquals(data("Bluetooth: on"), tool.content)
        assertTrue(plan.prompt.isEmpty())
        assertFalse(plan.seed.any { it.content.contains("Bluetooth: on") })
    }

    @Test
    fun `gemma native - multi round keeps every call and result in order`() {
        val messages = round2 + listOf(
            ChatPromptMessage("assistant", "", toolCalls = listOf(ChatPromptToolCall("set_bluetooth", "{\"enabled\":false}"))),
            ChatPromptMessage(ChatPromptMessage.ROLE_TOOL, "Opened Bluetooth settings", toolName = "set_bluetooth")
        )
        val plan = ChatTurnPlanner.plan(messages, layout = ToolTurnLayout.GEMMA_NATIVE)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), plan.seed.map { it.role })
        assertEquals("set_bluetooth", plan.promptTools.single().toolName)
    }

    @Test
    fun `gemma native - the next user turn after a tool round is a normal prompt`() {
        val messages = round2 + listOf(ChatPromptMessage("assistant", "Bluetooth is on."), user("thanks"))
        val plan = ChatTurnPlanner.plan(messages, layout = ToolTurnLayout.GEMMA_NATIVE)
        assertTrue(plan.promptTools.isEmpty())
        assertEquals("thanks", plan.prompt)
        assertEquals(listOf("user", "assistant", "tool", "assistant"), plan.seed.map { it.role })
    }

    @Test
    fun `gemma native - orphan results without a calling turn fall back to the user turn`() {
        val plan = ChatTurnPlanner.plan(listOf(sys("R"), user("q"), result("x")), layout = ToolTurnLayout.GEMMA_NATIVE)
        assertTrue(plan.promptTools.isEmpty())
        assertTrue(plan.prompt.startsWith("q"))
        assertTrue(plan.prompt.contains(data("x")))
    }

    @Test
    fun `gemma native - transcript after a reply continues the tool turn`() {
        val plan = ChatTurnPlanner.plan(round2, layout = ToolTurnLayout.GEMMA_NATIVE)
        val t = ChatTurnPlanner.transcriptAfterReply(plan, "Bluetooth is on.")
        assertEquals(listOf("user", "assistant", "tool", "assistant"), t.map { it.role })
        val next = ChatTurnPlanner.plan(
            round2 + listOf(ChatPromptMessage("assistant", "Bluetooth is on."), user("ok")),
            layout = ToolTurnLayout.GEMMA_NATIVE
        )
        assertTrue(ChatTurnPlanner.canReuse(ResidentSession("RULES", "k", t), next, "k"))
    }

    @Test
    fun `gemma native - overflow reseed never drops the calling turn of the prompt`() {
        val long = listOf(sys("R"), user("a"), ChatPromptMessage("assistant", "b"), user("c"), ChatPromptMessage("assistant", "d")) +
            round2.drop(1)
        val plan = ChatTurnPlanner.plan(long, layout = ToolTurnLayout.GEMMA_NATIVE)
        val seed = ChatTurnPlanner.overflowSeed(plan, keepTurns = 2)
        assertTrue(seed.last().toolCalls.isNotEmpty())
        assertEquals("user", seed.first().role)
    }

    @Test
    fun `qwen - official tool_call and tool_response layout`() {
        val plan = ChatTurnPlanner.plan(
            round2.dropLast(1) + listOf(result("Bluetooth: on", note = "hint"), result("Wi-Fi: on")),
            layout = ToolTurnLayout.QWEN
        )
        assertEquals(
            "<tool_call>\n{\"name\": \"get_device_info\", \"arguments\": {}}\n</tool_call>",
            plan.seed.last().content
        )
        assertEquals("assistant", plan.seed.last().role)
        assertTrue(
            plan.prompt,
            plan.prompt.startsWith(
                "<tool_response>\n${data("Bluetooth: on")}\n</tool_response>\n" +
                    "<tool_response>\n${data("Wi-Fi: on")}\n</tool_response>"
            )
        )
        assertTrue(plan.prompt.contains("get_device_info: hint"))
    }

    @Test
    fun `qwen - assistant text precedes the call blocks`() {
        assertEquals(
            "Checking.\n<tool_call>\n{\"name\": \"get_device_info\", \"arguments\": {}}\n</tool_call>",
            QwenToolLayout.assistantWithCalls("Checking.", listOf(call))
        )
    }

    @Test
    fun `legacy - results stay framed data in the user turn`() {
        val plan = ChatTurnPlanner.plan(round2, layout = ToolTurnLayout.LEGACY)
        assertTrue(plan.promptTools.isEmpty())
        assertTrue(plan.prompt.contains(data("Bluetooth: on")))
        assertTrue(plan.prompt.startsWith("turn off bluetooth"))
    }

    @Test
    fun `litert conversion - native tool turns use the model and tool roles`() {
        val plan = ChatTurnPlanner.plan(round2, layout = ToolTurnLayout.GEMMA_NATIVE)
        val model = LiteRtMessages.seed(plan.seed[1])
        assertEquals(Role.MODEL, model.role)
        assertEquals("get_device_info", model.toolCalls.single().name)
        val prompt = LiteRtMessages.prompt(plan)
        assertEquals(Role.TOOL, prompt.role)
        assertEquals(Role.USER, LiteRtMessages.prompt(ChatTurnPlanner.plan(listOf(user("hi")))).role)
        assertEquals(mapOf("enabled" to false, "n" to 3L, "s" to "x"), LiteRtMessages.argumentsMap("{\"enabled\":false,\"n\":3,\"s\":\"x\",\"z\":null}"))
    }
}
