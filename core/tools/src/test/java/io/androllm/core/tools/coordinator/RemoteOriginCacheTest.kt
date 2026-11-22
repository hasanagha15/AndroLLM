package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.agent.AgentContextBuilder
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.executor.ToolExecutor
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.planner.ToolPlanner
import io.androllm.core.tools.registry.ToolRegistry
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** Branch b-mqtt: remote-origin calls must never read or fill the shared result cache. */
class RemoteOriginCacheTest {

    private val executor = mockk<ToolExecutor>()
    private val registry = mockk<ToolRegistry>(relaxed = true)
    private val coordinator = ToolRunCoordinator(
        mockk<ToolPlanner>(), executor, mockk<AutomationSettingsStore>(relaxed = true),
        mockk<AgentContextBuilder>(relaxed = true), registry
    )
    private val call = ToolCall("c1", "calculate", buildJsonObject { })

    init {
        val tool = mockk<Tool>()
        every { tool.spec } returns ToolSpec(name = "calculate", description = "calc", cacheable = true)
        every { registry.get("calculate") } returns tool
        coEvery { executor.execute(any()) } returns ToolResult.Success("4")
    }

    @Test
    fun `owner calls use the cache, remote calls never replay or populate it`() = runTest {
        coordinator.executeCalls(listOf(call))            // owner: executes, fills cache
        coordinator.executeCalls(listOf(call))            // owner: cache hit
        coVerify(exactly = 1) { executor.execute(any()) }

        withContext(RemoteToolOrigin()) {
            coordinator.executeCalls(listOf(call))        // remote: must execute, not replay owner's result
            coordinator.executeCalls(listOf(call))        // remote: and must not have cached its own
        }
        coVerify(exactly = 3) { executor.execute(any()) }
    }
}
