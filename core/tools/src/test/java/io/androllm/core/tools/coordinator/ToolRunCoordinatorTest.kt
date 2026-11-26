package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.cloud.model.CloudToolCall
import io.androllm.core.cloud.model.CloudToolCallFunction
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolRunCoordinatorTest {

    private val planner = mockk<ToolPlanner>()
    private val executor = mockk<ToolExecutor>()
    private val settingsStore = mockk<AutomationSettingsStore>(relaxed = true)
    private val agentContext = mockk<AgentContextBuilder>(relaxed = true)
    private val registry = mockk<ToolRegistry>(relaxed = true)

    private fun coordinator() = ToolRunCoordinator(planner, executor, settingsStore, agentContext, registry)

    /** Registers a fake pure-read tool with the given name on the registry. */
    private fun registerReadTool(name: String) {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name = name, description = "read-only test tool", cacheable = true)
        every { registry.get(name) } returns tool
    }

    @Test
    fun `executeCloudToolCalls preserves text streamed alongside the tool calls`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Success("sent", buildJsonObject { })
        val call = CloudToolCall(
            id = "call_1",
            type = "function",
            function = CloudToolCallFunction("send_sms", """{"phone":"+1","message":"hi"}""")
        )
        val msgs = coordinator().executeCloudToolCalls(listOf(call), assistantContent = "Sending the weather to mom.")

        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].role).isEqualTo("assistant")
        // The interim narration must reach the next round so the answer is never partial.
        assertThat(msgs[0].content).isEqualTo("Sending the weather to mom.")
        assertThat(msgs[0].toolCalls).hasSize(1)
        assertThat(msgs[1].role).isEqualTo("tool")
        assertThat(msgs[1].toolCallId).isEqualTo("call_1")
        assertThat(msgs[1].content).contains("sent")
    }

    @Test
    fun `executeCloudToolCalls leaves content null when no interim text`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Failure("declined")
        val call = CloudToolCall(
            id = "call_2",
            function = CloudToolCallFunction("send_sms", "{}")
        )
        val msgs = coordinator().executeCloudToolCalls(listOf(call))
        assertThat(msgs[0].content).isNull()
    }

    @Test
    fun `same-name calls without provider ids get distinct confirmation ids`() = runTest {
        coEvery { executor.execute(any()) } returns ToolResult.Success("ok", buildJsonObject { })
        val calls = listOf(
            CloudToolCall(index = 0, function = CloudToolCallFunction("send_sms", """{"phone":"+1"}""")),
            CloudToolCall(index = 1, function = CloudToolCallFunction("send_sms", """{"phone":"+2"}"""))
        )
        val msgs = coordinator().executeCloudToolCalls(calls)
        assertThat(msgs).hasSize(3) // assistant + 2 tool results
        assertThat(msgs[1].toolCallId).isNotEqualTo(msgs[2].toolCallId)
    }

    @Test
    fun `empty calls return empty list`() = runTest {
        assertThat(coordinator().executeCloudToolCalls(emptyList())).isEmpty()
    }

    @Test
    fun `oversized tool output is chunked into sequential tool messages never truncated`() = runTest {
        registerReadTool("search_web")
        // ~20KB of results — far beyond the 8KB chunk budget.
        val big = "Result ".repeat(4_000)
        coEvery { executor.execute(any()) } returns ToolResult.Success(big, buildJsonObject { })
        val call = CloudToolCall(id = "call_x", function = CloudToolCallFunction("search_web", """{"query":"news"}"""))

        val msgs = coordinator().executeCloudToolCalls(listOf(call))

        // Assistant + one tool message per chunk; the FULL output survives.
        assertThat(msgs.size).isGreaterThan(2)
        assertThat(msgs[0].toolCalls).hasSize(msgs.size - 1)
        val joined = msgs.drop(1).joinToString("") { it.content.orEmpty() }
        assertThat(joined).contains("Result")
        // Nothing was dropped: chunk ids are all present and matched.
        msgs.drop(1).forEachIndexed { i, m ->
            assertThat(m.role).isEqualTo("tool")
            assertThat(m.toolCallId).isEqualTo(msgs[0].toolCalls!![i].id)
        }
    }

    @Test
    fun `identical cacheable call is replayed from cache instead of re-executing`() = runTest {
        registerReadTool("search_web")
        var executions = 0
        coEvery { executor.execute(any()) } answers {
            executions++
            ToolResult.Success("cached-search-output", buildJsonObject { })
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("search_web", """{"query":"weather"}"""))
        val coord = coordinator()

        val first = coord.executeCloudToolCalls(listOf(call))
        val second = coord.executeCloudToolCalls(listOf(call))

        assertThat(executions).isEqualTo(1)
        assertThat(first[1].content).isEqualTo("cached-search-output")
        assertThat(second[1].content).isEqualTo("cached-search-output")
    }

    @Test
    fun `non-cacheable tool never replays from cache`() = runTest {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name = "send_sms", description = "side-effecting test tool", cacheable = false)
        every { registry.get("send_sms") } returns tool
        var executions = 0
        coEvery { executor.execute(any()) } answers {
            executions++
            ToolResult.Success("sent", buildJsonObject { })
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("send_sms", """{"phone":"+1"}"""))
        val coord = coordinator()

        coord.executeCloudToolCalls(listOf(call))
        coord.executeCloudToolCalls(listOf(call))

        // Side-effecting tools MUST always execute — never replayed.
        assertThat(executions).isEqualTo(2)
    }

    @Test
    fun `transient tool failure is retried with backoff then succeeds`() = runTest {
        registerReadTool("get_weather")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            if (calls == 1) {
                ToolResult.Failure("network timeout", retryable = true)
            } else {
                ToolResult.Success("Sunny 24C", buildJsonObject { })
            }
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("get_weather", """{"location":"Berlin"}"""))

        val msgs = coordinator().executeCloudToolCalls(listOf(call))

        assertThat(calls).isEqualTo(2)
        assertThat(msgs[1].content).isEqualTo("Sunny 24C")
    }

    @Test
    fun `non-retryable failure is not retried`() = runTest {
        registerReadTool("get_weather")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            ToolResult.Failure("The user declined the action.", retryable = false)
        }
        val call = CloudToolCall(id = "call_1", function = CloudToolCallFunction("get_weather", """{"location":"Berlin"}"""))

        coordinator().executeCloudToolCalls(listOf(call))

        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `SensitiveAppGuard refusal is neither retried nor re-routed to an alternative tool`() = runTest {
        // Two accessibility tools of the same category/permission: a retryable
        // failure of ui_click could be re-routed to ui_type. The guard refusal
        // is non-retryable, so exactly one execution happens.
        val click = mockk<Tool>()
        val type = mockk<Tool>()
        every { click.spec } returns ToolSpec(
            name = "ui_click", description = "tap",
            permission = io.androllm.core.tools.api.ToolPermission.ACCESSIBILITY,
            category = io.androllm.core.tools.api.ToolCategory.DEVICE
        )
        every { type.spec } returns ToolSpec(
            name = "ui_type", description = "type",
            permission = io.androllm.core.tools.api.ToolPermission.ACCESSIBILITY,
            category = io.androllm.core.tools.api.ToolCategory.DEVICE
        )
        every { registry.get("ui_click") } returns click
        every { registry.get("ui_type") } returns type
        every { registry.all() } returns listOf(click, type)
        val executed = mutableListOf<String>()
        coEvery { executor.execute(any()) } answers {
            executed += firstArg<io.androllm.core.tools.api.ToolCall>().name
            ToolResult.Failure(
                "UI automation is blocked in com.teslamotors.tesla for safety. Use the dedicated tool.",
                retryable = false
            )
        }
        val call = CloudToolCall(id = "c1", function = CloudToolCallFunction("ui_click", """{"label":"Unlock"}"""))

        coordinator().executeCloudToolCalls(listOf(call))

        assertThat(executed).containsExactly("ui_click")
    }

    // ── Branch U: alternative-tool selection + retry policy ─────────────────

    private fun schema(vararg required: String, optional: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            (required.toList() + optional).forEach { put(it, buildJsonObject { put("type", "string") }) }
        })
        put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    private fun register(spec: ToolSpec): Tool {
        val tool = mockk<Tool>()
        every { tool.spec } returns spec
        every { registry.get(spec.name) } returns tool
        return tool
    }

    @Test
    fun `failed side-effecting tool is never re-routed - step 5 open_clock to set_flashlight`() = runTest {
        val clock = register(ToolSpec(name = "open_clock", description = "clock",
            permission = io.androllm.core.tools.api.ToolPermission.APPS, category = io.androllm.core.tools.api.ToolCategory.MEDIA))
        val torch = register(ToolSpec(name = "set_flashlight", description = "torch", parameters = schema("on"),
            permission = io.androllm.core.tools.api.ToolPermission.FLASHLIGHT, category = io.androllm.core.tools.api.ToolCategory.MEDIA))
        every { registry.all() } returns listOf(clock, torch)
        val executed = mutableListOf<String>()
        coEvery { executor.execute(any()) } answers {
            executed += firstArg<io.androllm.core.tools.api.ToolCall>().name
            ToolResult.Failure("Could not open the clock app.", retryable = true)
        }
        val coord = coordinator()
        assertThat(coord.findAlternativeTool("open_clock", io.androllm.core.tools.api.ToolCall("c", "open_clock", buildJsonObject { }))).isNull()
        coord.executeCalls(listOf(io.androllm.core.tools.api.ToolCall("c", "open_clock", buildJsonObject { })))
        assertThat(executed.toSet()).containsExactly("open_clock")
    }

    @Test
    fun `alternative is never the failed tool itself`() = runTest {
        val weather = register(ToolSpec(name = "get_weather", description = "w", parameters = schema("location"), cacheable = true))
        every { registry.all() } returns listOf(weather)
        val call = io.androllm.core.tools.api.ToolCall("c", "get_weather", buildJsonObject { put("location", "Baku") })
        assertThat(coordinator().findAlternativeTool("get_weather", call)).isNull()
    }

    @Test
    fun `alternative must be read-only and schema-compatible`() {
        val search = register(ToolSpec(name = "search_web", description = "s", parameters = schema("query"),
            category = io.androllm.core.tools.api.ToolCategory.INFORMATION, cacheable = true))
        val sideEffect = register(ToolSpec(name = "note_save", description = "n", parameters = schema("query"),
            category = io.androllm.core.tools.api.ToolCategory.INFORMATION, cacheable = false))
        val confirmRead = register(ToolSpec(name = "read_notifications", description = "r", parameters = schema("query"),
            category = io.androllm.core.tools.api.ToolCategory.INFORMATION, cacheable = true, requiresConfirmation = true))
        val otherSchema = register(ToolSpec(name = "get_weather", description = "w", parameters = schema("location"),
            category = io.androllm.core.tools.api.ToolCategory.INFORMATION, cacheable = true))
        val compatible = register(ToolSpec(name = "github", description = "g", parameters = schema("query", optional = listOf("type")),
            category = io.androllm.core.tools.api.ToolCategory.INFORMATION, cacheable = true))
        val call = io.androllm.core.tools.api.ToolCall("c", "search_web", buildJsonObject { put("query", "kotlin") })

        every { registry.all() } returns listOf(search, sideEffect, confirmRead, otherSchema)
        assertThat(coordinator().findAlternativeTool("search_web", call)).isNull()

        every { registry.all() } returns listOf(search, sideEffect, confirmRead, otherSchema, compatible)
        assertThat(coordinator().findAlternativeTool("search_web", call)?.name).isEqualTo("github")
    }

    @Test
    fun `permanent failure status is not retried`() = runTest {
        registerReadTool("get_battery")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            ToolResult.Failure("unsupported", data = buildJsonObject { put("status", "unsupported") }, retryable = true)
        }
        coordinator().executeCalls(listOf(io.androllm.core.tools.api.ToolCall("c", "get_battery", buildJsonObject { })))
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `retries never open extra Running cards`() = runTest {
        registerReadTool("get_weather")
        var calls = 0
        coEvery { executor.execute(any()) } answers {
            calls++
            ToolResult.Failure("network timeout", retryable = true)
        }
        val events = mutableListOf<io.androllm.core.tools.api.ToolEvent>()
        coordinator().executeCalls(
            listOf(io.androllm.core.tools.api.ToolCall("c", "get_weather", buildJsonObject { put("location", "Baku") })),
            onEvent = { events += it }
        )
        assertThat(calls).isEqualTo(3)
        val started = events.count { it is io.androllm.core.tools.api.ToolEvent.Started }
        val terminal = events.count { it !is io.androllm.core.tools.api.ToolEvent.Started }
        // Every Started has a matching terminal event, so no card stays "Running…".
        assertThat(started).isEqualTo(terminal)
        assertThat(started).isEqualTo(1)
    }
}
