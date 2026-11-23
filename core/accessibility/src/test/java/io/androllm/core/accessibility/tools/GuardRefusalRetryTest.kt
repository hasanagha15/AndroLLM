package io.androllm.core.accessibility.tools

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.tools.api.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** Н8: a SensitiveAppGuard refusal must reach the coordinator as NON-retryable. */
class GuardRefusalRetryTest {

    private val refusal = SensitiveAppGuard.blockedReason("com.teslamotors.tesla")!!

    private fun controller(result: UiActionResult): AccessibilityController {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.click(any(), any(), any()) } returns result
        coEvery { c.type(any(), any()) } returns result
        coEvery { c.scroll(any()) } returns result
        return c
    }

    @Test
    fun `blocked result is a non-retryable failure`() {
        val r = UiActionResult.blocked(refusal)
        assertThat(r.success).isFalse()
        assertThat(r.retryable).isFalse()
        assertThat(UiActionResult.fail("not found").retryable).isTrue()
    }

    @Test
    fun `ui_click guard refusal is not retryable`() = runTest {
        val tool = UiClickTool(controller(UiActionResult.blocked(refusal)))
        val result = tool.execute(buildJsonObject { put("label", JsonPrimitive("Unlock")) })
        assertThat(result).isInstanceOf(ToolResult.Failure::class.java)
        result as ToolResult.Failure
        assertThat(result.retryable).isFalse()
        assertThat(result.summary).contains("blocked")
    }

    @Test
    fun `ui_type guard refusal is not retryable`() = runTest {
        val tool = UiTypeTool(controller(UiActionResult.blocked(refusal)))
        val result = tool.execute(buildJsonObject { put("text", JsonPrimitive("hi")) }) as ToolResult.Failure
        assertThat(result.retryable).isFalse()
    }

    @Test
    fun `ui_scroll guard refusal is not retryable`() = runTest {
        val tool = UiScrollTool(controller(UiActionResult.blocked(refusal)))
        val result = tool.execute(buildJsonObject { put("direction", JsonPrimitive("down")) }) as ToolResult.Failure
        assertThat(result.retryable).isFalse()
    }

    @Test
    fun `an ordinary miss stays retryable`() = runTest {
        val tool = UiClickTool(controller(UiActionResult.fail("Element 'x' was not found on this screen.")))
        val result = tool.execute(buildJsonObject { put("label", JsonPrimitive("x")) }) as ToolResult.Failure
        assertThat(result.retryable).isTrue()
    }
}
