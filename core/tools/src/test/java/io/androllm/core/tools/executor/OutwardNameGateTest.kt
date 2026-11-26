package io.androllm.core.tools.executor

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.settings.ConfirmationMode
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * Branch U it.2 (gate B1): MCP / plugin tools whose (camelCase) names say they
 * send, post or delete raise a tap-only card even though their spec does not ask
 * for confirmation (McpRemoteTool: requiresConfirmation=false), and a spoken
 * "yes" can never approve it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutwardNameGateTest {

    private class FakeMcpTool(name: String) : Tool {
        var executions = 0
        override val spec = ToolSpec(name = name, description = "mcp test tool", requiresConfirmation = false)
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult.Success("done")
        }
    }

    private val names = listOf("mcp_srv_sendMessage", "mcp_srv_postTweet", "deleteFile", "mcp_shop_checkoutCart")
    private val tools = names.map { FakeMcpTool(it) }
    private val manager = ToolConfirmationManager()
    private var settings = AutomationSettings()
    private val executor = run {
        val registry = ToolRegistry().apply { tools.forEach { registerStrict(it) } } // camelCase names (MCP SDK) bypass the snake_case check here
        val store = mockk<AutomationSettingsStore>()
        coEvery { store.current() } answers { settings }
        ToolExecutor(registry, store, manager, ToolExecutionTraceStore())
    }

    @Test
    fun `camelCase outward names are tap-only by name`() {
        for (n in names + listOf("mcp_x_sendEmail", "uploadPhoto", "inviteUser", "trashItem", "unlockDoor", "buyNow", "placeOrder")) {
            assertThat(ToolConfirmationManager.isTapOnlyToolName(n)).isTrue()
        }
        for (n in listOf("mcp_srv_getWeather", "readFile", "listOrders2", "calendar", "set_alarm")) {
            assertThat(ToolConfirmationManager.isTapOnlyToolName(n)).isFalse()
        }
    }

    @Test
    fun `each outward mcp call raises a card that a spoken yes cannot approve`() = runTest {
        var voiceAsked = false
        manager.setVoiceResponder { voiceAsked = true; true }
        for ((i, tool) in tools.withIndex()) {
            val call = ToolCall(id = "c$i", name = tool.spec.name, arguments = buildJsonObject { })
            val r = backgroundScope.async { executor.execute(call) }
            runCurrent()
            val card = manager.pending.value
            assertThat(card).isNotNull()
            assertThat(ToolConfirmationManager.isTapOnly(card!!)).isTrue()
            assertThat(tool.executions).isEqualTo(0)
            manager.deny(card.id)
            runCurrent()
            assertThat(r.await().isSuccess).isFalse()
            assertThat(tool.executions).isEqualTo(0)
        }
        assertThat(voiceAsked).isFalse()
    }

    @Test
    fun `owner mode NEVER is honoured for name-matched tools`() = runTest {
        settings = AutomationSettings(confirmationMode = ConfirmationMode.NEVER)
        val r = executor.execute(ToolCall(id = "n", name = "deleteFile", arguments = buildJsonObject { }))
        assertThat(r.isSuccess).isTrue()
        assertThat(manager.pending.value).isNull()
    }
}
