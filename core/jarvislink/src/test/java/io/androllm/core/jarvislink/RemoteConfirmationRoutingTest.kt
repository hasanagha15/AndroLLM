package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.confirm.ConfirmationNotifier
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultMessage
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * Proves a remote task goes through the REAL confirmation gate:
 * JarvisTaskRunner → ToolExecutor → ToolConfirmationManager.awaitDecision.
 * The router only adds a notification surface; nothing approves on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConfirmationRoutingTest {

    private class FakeSmsTool : Tool {
        var executions = 0
        override val spec = ToolSpec(
            name = "send_sms",
            description = "Send an SMS (test double)",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                }
            },
            requiresConfirmation = true,
            confirmationPrompt = "send the SMS"
        )
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult.Success("SMS sent")
        }
    }

    private class RecordingNotifier : ConfirmationNotifier {
        val shown = mutableListOf<Pair<PendingToolConfirmation, JarvisTask>>()
        var cancels = 0
        var visible: PendingToolConfirmation? = null
        override fun show(confirmation: PendingToolConfirmation, task: JarvisTask) {
            shown += confirmation to task; visible = confirmation
        }
        override fun cancel() { cancels++; visible = null }
    }

    private val now = 1_800_000_000_000L
    private val sms = FakeSmsTool()
    private val manager = ToolConfirmationManager()
    private val tracker = RemoteTaskTracker()
    private val notifier = RecordingNotifier()
    private val router = RemoteConfirmationRouter(manager, tracker, notifier)
    private val published = mutableListOf<ResultMessage>()

    private val executor: ToolExecutor = run {
        val registry = ToolRegistry().apply { check(register(sms)) }
        val settings = mockk<AutomationSettingsStore>()
        coEvery { settings.current() } returns AutomationSettings() // default: HIGH_RISK confirmations
        ToolExecutor(registry, settings, manager, ToolExecutionTraceStore())
    }

    /** Stands in for the chat pipeline: the "model" decides to call send_sms. */
    private val agentRunner = JarvisTaskRunner { task ->
        val result = executor.execute(
            ToolCall(id = "call-${task.id}", name = "send_sms", arguments = buildJsonObject { put("message", task.text) })
        )
        TaskOutcome(ResultStatus.OK, result.summary)
    }

    private fun TestScope.start(): RemoteTaskProcessor {
        router.start(backgroundScope)
        return RemoteTaskProcessor(
            runner = agentRunner,
            publisher = { published += JarvisProtocol.decodeResult(it) },
            deduplicator = TaskDeduplicator(),
            tracker = tracker,
            clock = { now }
        ).also { it.start(backgroundScope) }
    }

    private fun payload(id: String) = """{"id":"$id","text":"tell mom I'm late"}""".toByteArray()
    private fun final(id: String) = published.last { it.id == id && it.status.isFinal }

    @Test
    fun `dangerous tool from remote task waits for approval and runs only after approve`() = runTest {
        val p = start()
        p.onPayload(payload("r1"))
        runCurrent()

        // Pending in the shared manager (chat card) AND surfaced as notification for this task.
        assertThat(manager.pending.value?.toolName).isEqualTo("send_sms")
        assertThat(notifier.visible?.id).isEqualTo("call-r1")
        assertThat(notifier.shown.single().second.id).isEqualTo("r1")
        assertThat(sms.executions).isEqualTo(0)
        assertThat(published.none { it.id == "r1" && it.status.isFinal }).isTrue()

        assertThat(router.approve("call-r1")).isTrue()
        runCurrent()
        assertThat(sms.executions).isEqualTo(1)
        assertThat(final("r1").status).isEqualTo(ResultStatus.OK)
        assertThat(final("r1").text).isEqualTo("SMS sent")
        assertThat(notifier.visible).isNull()
    }

    @Test
    fun `deny from notification blocks the tool`() = runTest {
        val p = start()
        p.onPayload(payload("r2"))
        runCurrent()
        assertThat(router.deny("call-r2")).isTrue()
        runCurrent()
        assertThat(sms.executions).isEqualTo(0)
        assertThat(final("r2").text).contains("declined")
    }

    @Test
    fun `chat card decision is honoured for remote tasks too`() = runTest {
        val p = start()
        p.onPayload(payload("r3"))
        runCurrent()
        manager.deny(manager.pending.value!!.id) // the user tapped Deny on the in-app card
        runCurrent()
        assertThat(sms.executions).isEqualTo(0)
        assertThat(notifier.visible).isNull()
    }

    @Test
    fun `unanswered confirmation times out as deny - never auto approve`() = runTest {
        val p = start()
        p.onPayload(payload("r4"))
        runCurrent()
        advanceTimeBy(ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS + 1_000)
        runCurrent()
        assertThat(sms.executions).isEqualTo(0)
        assertThat(final("r4").text).contains("declined")
    }

    @Test
    fun `stale or foreign ids cannot approve`() = runTest {
        val p = start()
        p.onPayload(payload("r5"))
        runCurrent()
        assertThat(router.approve("call-other")).isFalse()
        runCurrent()
        assertThat(sms.executions).isEqualTo(0)
        assertThat(manager.pending.value?.id).isEqualTo("call-r5")
        router.deny("call-r5")
        runCurrent()
        assertThat(router.approve("call-r5")).isFalse() // already resolved
        assertThat(sms.executions).isEqualTo(0)
    }

    @Test
    fun `confirmation of a local typed request is not routed to the notification`() = runTest {
        router.start(backgroundScope)
        val typed = backgroundScope.launch {
            executor.execute(ToolCall(id = "typed-1", name = "send_sms", arguments = buildJsonObject { put("message", "hi") }))
        }
        runCurrent()
        assertThat(manager.pending.value?.id).isEqualTo("typed-1")
        assertThat(notifier.shown).isEmpty() // chat card only
        manager.confirm("typed-1")
        runCurrent()
        assertThat(typed.isCompleted).isTrue()
        assertThat(sms.executions).isEqualTo(1)
    }
}
