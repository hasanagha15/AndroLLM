package io.androllm.core.tools.executor

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.api.runtimePermissions
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * Branch U (step 5): set_flashlight without CAMERA failed and was retried three
 * times. A tool that reports a missing runtime permission now raises a tap-only
 * permission card whose requiredPermissions the chat requests on Approve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionPromptGateTest {

    private class FakeTorch : Tool {
        var granted = false
        var executions = 0
        override val spec = ToolSpec(
            name = "set_flashlight",
            description = "torch",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("on") { put("type", "boolean") } }
            },
            permission = ToolPermission.FLASHLIGHT
        )
        override fun missingRuntimePermissions(): List<String> =
            if (granted) emptyList() else listOf("android.permission.CAMERA")
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult.Success("on")
        }
    }

    private val tool = FakeTorch()
    private val manager = ToolConfirmationManager()
    private val executor = run {
        val registry = ToolRegistry().apply { check(register(tool)) }
        val store = mockk<AutomationSettingsStore>()
        coEvery { store.current() } returns AutomationSettings()
        ToolExecutor(registry, store, manager, ToolExecutionTraceStore())
    }

    private val call = ToolCall(id = "torch-1", name = "set_flashlight", arguments = buildJsonObject { put("on", true) })

    @Test
    fun `missing permission raises a tap-only permission card instead of failing`() = runTest {
        var voiceAsked = false
        manager.setVoiceResponder { voiceAsked = true; true }
        val r = backgroundScope.async { executor.execute(call) }
        runCurrent()
        val card = manager.pending.value!!
        assertThat(card.permissionPrompt).isTrue()
        assertThat(card.requiredPermissions).containsExactly("android.permission.CAMERA")
        assertThat(ToolConfirmationManager.isTapOnly(card)).isTrue()
        assertThat(voiceAsked).isFalse()
        tool.granted = true // the system dialog granted it
        manager.confirm(card.id)
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(tool.executions).isEqualTo(1)
    }

    @Test
    fun `granted permission runs without any card`() = runTest {
        tool.granted = true
        val r = executor.execute(call)
        assertThat(r.isSuccess).isTrue()
        assertThat(manager.pending.value).isNull()
    }

    @Test
    fun `denied permission card is a non-retryable decline`() = runTest {
        val r = backgroundScope.async { executor.execute(call) }
        runCurrent()
        manager.deny(manager.pending.value!!.id)
        runCurrent()
        val result = r.await() as ToolResult.Failure
        assertThat(result.retryable).isFalse()
        assertThat(tool.executions).isEqualTo(0)
    }

    @Test
    fun `flashlight capability maps to the CAMERA runtime permission`() {
        assertThat(ToolPermission.FLASHLIGHT.runtimePermissions())
            .containsExactly(android.Manifest.permission.CAMERA)
    }
}
