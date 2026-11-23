package io.androllm.core.tools.executor

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.confirmation.isRiskyActionApproved
import io.androllm.core.tools.origin.RemoteToolOrigin
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * Branch M: a tool call that declares [Tool.riskyAction] (ui_click "Send",
 * "Отправить", "Göndər", Pay, Delete…) always gets a TAP-ONLY card — whatever
 * the confirmation mode — and runs inside RiskyActionApproval only when tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RiskyActionGateTest {

    /** Stand-in for ui_click: risky when the label is in [riskyLabels]. */
    private class FakeUiClick : Tool {
        val riskyLabels = setOf("Send", "Отправить", "Göndər")
        var executions = 0
        var approvedInside: Boolean? = null
        override val spec = ToolSpec(
            name = "ui_click",
            description = "test ui_click",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("label") { put("type", "string") } }
            }
        )
        override fun riskyAction(arguments: JsonObject): String? {
            val label = (arguments["label"] as? JsonPrimitive)?.content ?: return null
            return if (label in riskyLabels) "tap \"$label\"" else null
        }
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            approvedInside = isRiskyActionApproved()
            return ToolResult.Success("tapped")
        }
    }

    private val tool = FakeUiClick()
    private val manager = ToolConfirmationManager()
    private var settings = AutomationSettings()
    private val executor = run {
        val registry = ToolRegistry().apply { check(register(tool)) }
        val store = mockk<AutomationSettingsStore>()
        coEvery { store.current() } answers { settings }
        ToolExecutor(registry, store, manager, ToolExecutionTraceStore())
    }

    private fun click(label: String, id: String = "call-$label") =
        ToolCall(id = id, name = "ui_click", arguments = buildJsonObject { put("label", label) })

    @Test
    fun `risky label raises a tap-only card that a spoken yes cannot approve`() = runTest {
        var voiceAsked = false
        manager.setVoiceResponder { voiceAsked = true; true }
        val r = backgroundScope.async { executor.execute(click("Отправить")) }
        runCurrent()
        val card = manager.pending.value!!
        assertThat(card.tapOnly).isTrue()
        assertThat(ToolConfirmationManager.isTapOnly(card)).isTrue()
        assertThat(card.actionSummary).contains("Отправить")
        assertThat(voiceAsked).isFalse()
        assertThat(tool.executions).isEqualTo(0)
        manager.confirm(card.id)
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(tool.executions).isEqualTo(1)
        assertThat(tool.approvedInside).isTrue()
    }

    @Test
    fun `confirmation mode NEVER does not skip a risky tap`() = runTest {
        settings = AutomationSettings(confirmationMode = ConfirmationMode.NEVER)
        val r = backgroundScope.async { executor.execute(click("Göndər")) }
        runCurrent()
        val card = manager.pending.value!!
        assertThat(card.tapOnly).isTrue()
        manager.deny(card.id)
        runCurrent()
        val result = r.await()
        assertThat(result).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(tool.executions).isEqualTo(0)
    }

    @Test
    fun `benign label runs without a card and without approval`() = runTest {
        val r = backgroundScope.async { executor.execute(click("Search")) }
        runCurrent()
        assertThat(manager.pending.value).isNull()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(tool.approvedInside).isFalse()
    }

    @Test
    fun `remote origin keeps its card, marked risky and tap-only`() = runTest {
        val remote = RemoteToolOrigin()
        val r = backgroundScope.async { withContext(remote) { executor.execute(click("Send")) } }
        runCurrent()
        val card = manager.pending.value!!
        assertThat(card.remoteOrigin).isEqualTo(RemoteToolOrigin.DEFAULT_LABEL)
        assertThat(card.actionSummary).startsWith("⚠ tap \"Send\"")
        assertThat(card.actionSummary).contains("label = Send")
        assertThat(ToolConfirmationManager.isTapOnly(card)).isTrue()
        manager.confirm(card.id)
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(tool.approvedInside).isTrue()
    }

    @Test
    fun `remote origin with confirmations NEVER is still refused outright`() = runTest {
        settings = AutomationSettings(confirmationMode = ConfirmationMode.NEVER)
        val r = withContext(RemoteToolOrigin()) { executor.execute(click("Send")) }
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(manager.pending.value).isNull()
        assertThat(tool.executions).isEqualTo(0)
    }
}
