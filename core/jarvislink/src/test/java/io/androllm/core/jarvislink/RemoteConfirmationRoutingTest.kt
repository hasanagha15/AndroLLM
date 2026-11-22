package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.confirm.ConfirmationNotifier
import io.androllm.core.jarvislink.confirm.RemoteConfirmationRouter
import io.androllm.core.jarvislink.processing.RemoteAgentRunner
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.RemoteTurnExecutor
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.agent.AgentVariableStore
import io.androllm.core.tools.agent.DeviceContextProvider
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.origin.RemoteToolPolicy
import io.androllm.core.tools.origin.setForCurrentOrigin
import io.androllm.core.tools.origin.snapshotForCurrentOrigin
import io.androllm.core.tools.tool.impl.WebSearchTool
import io.mockk.every
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.settings.ConfirmationMode
import io.androllm.core.tools.trace.ToolExecutionTraceStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/**
 * The remote policy is enforced by the REAL ToolExecutor + ToolConfirmationManager
 * whenever the call runs inside RemoteToolOrigin (which RemoteAgentRunner sets).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConfirmationRoutingTest {

    private class FakeTool(
        private val name: String,
        cacheable: Boolean = false,
        requiresConfirmation: Boolean = false
    ) : Tool {
        var executions = 0
        override val spec = ToolSpec(
            name = name,
            description = "test double $name",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                    putJsonObject("app") { put("type", "string") }
                }
            },
            requiresConfirmation = requiresConfirmation,
            cacheable = cacheable
        )
        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult.Success("$name done")
        }
    }

    private class RecordingNotifier : ConfirmationNotifier {
        val shown = mutableListOf<PendingToolConfirmation>()
        var visible: PendingToolConfirmation? = null
        override fun show(confirmation: PendingToolConfirmation, task: JarvisTask?) { shown += confirmation; visible = confirmation }
        override fun cancel() { visible = null }
    }

    private val sms = FakeTool("send_sms", requiresConfirmation = true)
    private val uiClick = FakeTool("ui_click")                       // unflagged by the base app
    private val mcpRead = FakeTool("mcp_files_list", cacheable = true) // MCP is never auto-allowed
    private val weather = FakeTool("calculate", cacheable = true)   // on the remote allowlist
    private val launch = FakeTool("launch_app")
    private val search = FakeTool("search_web", cacheable = true)   // cacheable, but can reach arbitrary hosts
    private val uiRun = FakeTool("ui_run")

    private var settings = AutomationSettings() // HIGH_RISK by default
    private val manager = ToolConfirmationManager()
    private val tracker = RemoteTaskTracker()
    private val notifier = RecordingNotifier()
    private val router = RemoteConfirmationRouter(manager, tracker, notifier)
    private val remote = RemoteToolOrigin(ownPackages = setOf("io.androllm.app"))

    private val executor: ToolExecutor = run {
        val registry = ToolRegistry()
        listOf(sms, uiClick, mcpRead, weather, launch, search, uiRun).forEach { check(registry.register(it)) }
        val store = mockk<AutomationSettingsStore>()
        coEvery { store.current() } answers { settings }
        ToolExecutor(registry, store, manager, ToolExecutionTraceStore())
    }

    private fun call(name: String, id: String = "call-$name", vararg args: Pair<String, String>) =
        ToolCall(id = id, name = name, arguments = buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    private fun TestScope.remoteCall(c: ToolCall): Deferred<ToolResult> =
        backgroundScope.async { withContext(remote) { executor.execute(c) } }

    private fun TestScope.localCall(c: ToolCall): Deferred<ToolResult> =
        backgroundScope.async { executor.execute(c) }

    // ── B1: what needs a tap ──────────────────────────────────────────────

    @Test
    fun `remote read-only tool runs without a card`() = runTest {
        val r = remoteCall(call("calculate"))
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(weather.executions).isEqualTo(1)
        assertThat(notifier.shown).isEmpty()
    }

    @Test
    fun `remote ui tool needs a card even though the base app does not flag it`() = runTest {
        router.start(backgroundScope)
        val r = remoteCall(call("ui_click", args = arrayOf("message" to "Send")))
        runCurrent()
        assertThat(uiClick.executions).isEqualTo(0)
        val pending = manager.pending.value!!
        assertThat(pending.remoteOrigin).isEqualTo(RemoteToolOrigin.DEFAULT_LABEL)
        assertThat(pending.toolDisplayName).contains("Jarvis (удалённо)")
        assertThat(notifier.visible?.id).isEqualTo("call-ui_click")
        assertThat(router.approve("call-ui_click")).isTrue()
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(uiClick.executions).isEqualTo(1)
    }

    @Test
    fun `same ui tool typed locally keeps base behaviour - no card`() = runTest {
        val r = localCall(call("ui_click"))
        runCurrent()
        assertThat(r.await().isSuccess).isTrue()
        assertThat(manager.pending.value).isNull()
    }

    @Test
    fun `remote mcp tool needs a card even if cacheable`() = runTest {
        remoteCall(call("mcp_files_list"))
        runCurrent()
        assertThat(manager.pending.value?.toolName).isEqualTo("mcp_files_list")
        assertThat(mcpRead.executions).isEqualTo(0)
    }

    @Test
    fun `remote card shows full arguments, not 40 chars`() = runTest {
        val longText = "Привет! " + "x".repeat(200)
        remoteCall(call("send_sms", args = arrayOf("message" to longText)))
        runCurrent()
        assertThat(manager.pending.value!!.actionSummary).contains(longText)
    }

    // ── B1: NEVER and settings re-checked per call ───────────────────────

    @Test
    fun `switching to NEVER mid task refuses the next remote call instead of skipping the card`() = runTest {
        val first = remoteCall(call("calculate"))
        runCurrent()
        assertThat(first.await().isSuccess).isTrue()
        settings = settings.copy(confirmationMode = ConfirmationMode.NEVER) // e.g. flipped via UI mid-task
        val second = remoteCall(call("send_sms", args = arrayOf("message" to "hi")))
        runCurrent()
        val r = second.await()
        assertThat(r.isSuccess).isFalse()
        assertThat(r.summary).contains("Never")
        assertThat(sms.executions).isEqualTo(0)
        assertThat(manager.pending.value).isNull()
    }

    @Test
    fun `remote call aimed at AndroLLM itself is refused`() = runTest {
        val r = remoteCall(call("launch_app", args = arrayOf("app" to "AndroLLM")))
        val r2 = remoteCall(call("ui_click", id = "c2", args = arrayOf("app" to "io.androllm.app")))
        runCurrent()
        assertThat(r.await().summary).contains("may not operate")
        assertThat(r2.await().isSuccess).isFalse()
        assertThat(launch.executions + uiClick.executions).isEqualTo(0)
    }

    // ── B1: no voice approval for remote cards ───────────────────────────

    @Test
    fun `voice responder is never asked for a remote card`() = runTest {
        // (that a LOCAL card does use the responder is covered by ToolConfirmationManagerTest)
        var voiceAsked = 0
        manager.setVoiceResponder { voiceAsked++; true } // would approve if it were asked
        val remoteResult = remoteCall(call("send_sms", args = arrayOf("message" to "hi")))
        runCurrent()
        Thread.sleep(200) // the responder would run on Dispatchers.Default
        runCurrent()
        assertThat(voiceAsked).isEqualTo(0)
        assertThat(sms.executions).isEqualTo(0)
        assertThat(manager.pending.value?.id).isEqualTo("call-send_sms")
        manager.deny("call-send_sms")
        runCurrent()
        assertThat(remoteResult.await().isSuccess).isFalse()
    }

    @Test
    fun `cards set without the executor (ui_run steps) are labelled remote from context too`() = runTest {
        var voiceAsked = 0
        manager.setVoiceResponder { voiceAsked++; true }
        backgroundScope.async {
            withContext(remote) {
                manager.awaitDecision(
                    PendingToolConfirmation("ui_step", "ui_automation", "UI Automation", "proceed", "Do it?")
                )
            }
        }
        runCurrent()
        assertThat(manager.pending.value?.remoteOrigin).isNotNull()
        assertThat(voiceAsked).isEqualTo(0)
    }

    // ── deny / timeout / stale ids ───────────────────────────────────────

    @Test
    fun `deny and unanswered timeout both block the tool`() = runTest {
        router.start(backgroundScope)
        val a = remoteCall(call("send_sms", args = arrayOf("message" to "1")))
        runCurrent()
        router.deny("call-send_sms")
        runCurrent()
        assertThat(a.await().summary).contains("declined")
        val b = remoteCall(call("send_sms", id = "c-b", args = arrayOf("message" to "2")))
        runCurrent()
        advanceTimeBy(ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS + 1_000)
        runCurrent()
        assertThat(b.await().summary).contains("declined")
        assertThat(sms.executions).isEqualTo(0)
    }

    @Test
    fun `router cannot approve foreign, stale or local cards`() = runTest {
        router.start(backgroundScope)
        localCall(call("send_sms", id = "typed-1", args = arrayOf("message" to "hi")))
        runCurrent()
        assertThat(notifier.shown).isEmpty()                 // local card is not mirrored
        assertThat(router.approve("typed-1")).isFalse()      // and cannot be approved from the remote surface
        manager.deny("typed-1")
        runCurrent()
        remoteCall(call("send_sms", id = "r-1", args = arrayOf("message" to "hi")))
        runCurrent()
        assertThat(router.approve("other")).isFalse()
        router.deny("r-1")
        runCurrent()
        assertThat(router.approve("r-1")).isFalse()
        assertThat(sms.executions).isEqualTo(0)
    }

    // ── B4: cards queue instead of replacing each other ──────────────────

    @Test
    fun `concurrent local and remote confirmations are queued, not replaced`() = runTest {
        val local = localCall(call("send_sms", id = "typed", args = arrayOf("message" to "a")))
        runCurrent()
        val remoteR = remoteCall(call("send_sms", id = "remote", args = arrayOf("message" to "b")))
        runCurrent()
        assertThat(manager.pending.value?.id).isEqualTo("typed") // not replaced by the remote card
        manager.confirm("typed")
        runCurrent()
        assertThat(local.await().isSuccess).isTrue()
        assertThat(manager.pending.value?.id).isEqualTo("remote")
        manager.deny("remote")
        runCurrent()
        assertThat(remoteR.await().isSuccess).isFalse()
        assertThat(sms.executions).isEqualTo(1)
    }

    // ── runner level: NEVER refusal, origin propagation, owner busy ───────

    private fun task(id: String = "t1") = JarvisTask(id, "do it", "jarvis", 0L, 600)

    @Test
    fun `runner refuses the whole task when confirmations are NEVER`() = runTest {
        settings = settings.copy(confirmationMode = ConfirmationMode.NEVER)
        var turns = 0
        val runner = RemoteAgentRunner(
            turns = RemoteTurnExecutor { turns++; flow { emit("x") } },
            automationSettings = { settings },
            ownerBusy = MutableStateFlow(false)
        )
        val out = runner.run(task())
        assertThat(out.status).isEqualTo(ResultStatus.DENIED)
        assertThat(turns).isEqualTo(0)
    }

    @Test
    fun `runner executes the turn inside RemoteToolOrigin so the executor applies the policy`() = runTest {
        val runner = RemoteAgentRunner(
            turns = RemoteTurnExecutor { prompt ->
                flow {
                    val r = executor.execute(call("ui_click", args = arrayOf("message" to prompt.take(30))))
                    emit("result: ${r.summary}")
                }
            },
            automationSettings = { settings },
            ownerBusy = MutableStateFlow(false),
            origin = remote
        )
        val out = backgroundScope.async { runner.run(task()) }
        runCurrent()
        assertThat(manager.pending.value?.remoteOrigin).isNotNull() // card raised → origin propagated through the flow
        manager.deny(manager.pending.value!!.id)
        runCurrent()
        assertThat(out.await().text).contains("declined")
        assertThat(uiClick.executions).isEqualTo(0)
    }

    @Test
    fun `runner waits for the owner's turn to finish`() = runTest {
        val busy = MutableStateFlow(true)
        var started = false
        val runner = RemoteAgentRunner(
            turns = RemoteTurnExecutor { started = true; flow { emit("ok") } },
            automationSettings = { settings },
            ownerBusy = busy
        )
        val out = backgroundScope.async { runner.run(task()) }
        runCurrent()
        assertThat(started).isFalse()
        busy.value = false
        runCurrent()
        assertThat(out.await()).isEqualTo(TaskOutcome(ResultStatus.OK, "ok"))
    }

    @Test
    fun `pipeline error strings become generic errors`() {
        assertThat(RemoteAgentRunner.classify("Error: java.io.IOException /secret", true).status).isEqualTo(ResultStatus.ERROR)
        assertThat(RemoteAgentRunner.classify("Error: java.io.IOException /secret", true).text).doesNotContain("secret")
        assertThat(RemoteAgentRunner.classify("I don't have a model loaded yet.", true).status).isEqualTo(ResultStatus.ERROR)
        assertThat(RemoteAgentRunner.classify("", true).status).isEqualTo(ResultStatus.ERROR)
        assertThat(RemoteAgentRunner.classify("partial", false).status).isEqualTo(ResultStatus.ERROR)
    }

    // ── re-gate B1: explicit allowlist, search_web, Always, ui_run, long args ──

    @Test
    fun `remote allowlist is exactly calculator, battery and weather`() {
        assertThat(RemoteToolPolicy.AUTO_ALLOWED).containsExactly("calculate", "get_battery", "get_weather")
    }

    @Test
    fun `remote search_web needs a card even though it is cacheable`() = runTest {
        remoteCall(call("search_web", args = arrayOf("message" to "weather in Baku")))
        runCurrent()
        assertThat(manager.pending.value?.toolName).isEqualTo("search_web")
        assertThat(search.executions).isEqualTo(0)
    }

    @Test
    fun `owner mode Always is honoured for remote allowlisted tools`() = runTest {
        settings = settings.copy(confirmationMode = ConfirmationMode.ALWAYS)
        remoteCall(call("calculate"))
        runCurrent()
        assertThat(manager.pending.value?.toolName).isEqualTo("calculate")
        assertThat(weather.executions).isEqualTo(0)
    }

    @Test
    fun `ui_run is refused for remote origin, untouched for the owner`() = runTest {
        val r = remoteCall(call("ui_run", args = arrayOf("message" to "open settings")))
        runCurrent()
        assertThat(r.await().summary).contains("not available to remote tasks")
        assertThat(manager.pending.value).isNull()
        val local = localCall(call("ui_run", id = "own", args = arrayOf("message" to "x")))
        runCurrent()
        assertThat(local.await().isSuccess).isTrue()
    }

    @Test
    fun `remote argument too long to show in full is refused, never truncated on the card`() = runTest {
        val r = remoteCall(call("send_sms", args = arrayOf("message" to "z".repeat(RemoteToolPolicy.MAX_ARG_CHARS + 1))))
        runCurrent()
        assertThat(r.await().summary).contains("could not be shown in full")
        assertThat(manager.pending.value).isNull()
        // at the limit: shown completely
        val ok = "y".repeat(RemoteToolPolicy.MAX_ARG_CHARS)
        remoteCall(call("send_sms", id = "c2", args = arrayOf("message" to ok)))
        runCurrent()
        assertThat(manager.pending.value!!.actionSummary).contains(ok)
    }

    @Test
    fun `web search refuses bang and redirect shortcuts and oversized queries`() {
        listOf("!g secret", "\\example", "weather !w Baku", "x".repeat(WebSearchTool.MAX_QUERY_CHARS + 1), "")
            .forEach { assertThat(WebSearchTool.rejectReason(it)).isNotNull() }
        listOf("weather in Baku", "price of BTC!", "C# vs Kotlin").forEach { assertThat(WebSearchTool.rejectReason(it)).isNull() }
    }

    // ── re-gate B2 / N2: context and variables of a remote turn ─────────────

    private fun contextBuilder(store: AgentVariableStore): AgentContextBuilder {
        val device = mockk<DeviceContextProvider>()
        every { device.collect(any()) } answers {
            val includePrivate = firstArg<Boolean>()
            listOf("time: now", "battery: 80%") +
                if (includePrivate) listOf("clipboard: hunter2", "current_app: Bank") else emptyList()
        }
        return AgentContextBuilder(device, store)
    }

    @Test
    fun `remote turn context has no clipboard, foreground app or owner variables`() = runTest {
        val store = AgentVariableStore().apply { beginTurn("owner-chat"); set("last_tool_output", "owner notes: PIN 1234") }
        val builder = contextBuilder(store)
        val ownerBlock = builder.buildBlockForCurrentOrigin()
        assertThat(ownerBlock).contains("clipboard:")
        assertThat(ownerBlock).contains("PIN 1234")
        val remoteBlock = withContext(remote) { builder.buildBlockForCurrentOrigin() }
        assertThat(remoteBlock).doesNotContain("clipboard:")
        assertThat(remoteBlock).doesNotContain("current_app:")
        assertThat(remoteBlock).doesNotContain("PIN 1234")
        assertThat(remoteBlock).contains("battery: 80%")
    }

    @Test
    fun `remote turn variables are isolated from the owner's scope in both directions`() = runTest {
        val store = AgentVariableStore().apply { beginTurn("owner-chat"); set("weather", "owner value") }
        withContext(remote) {
            store.setForCurrentOrigin("weather", "remote value")
            assertThat(store.snapshotForCurrentOrigin()).containsExactly("weather", "remote value")
        }
        assertThat(store.snapshot()).containsExactly("weather", "owner value")
        assertThat(store.snapshotForCurrentOrigin()).containsExactly("weather", "owner value")
    }
}
