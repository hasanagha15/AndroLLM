package io.androllm.core.jarvistools.glasses

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvistools.FakeGate
import io.androllm.core.tools.api.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class GlassesToolTest {

    private class RecordingDisplay(var reason: String? = null) : GlassesDisplay {
        val shown = mutableListOf<Pair<String, String>>()
        override fun unavailableReason() = reason
        override fun show(title: String, text: String): String? {
            shown += title to text
            return null
        }
    }

    @Test fun requiresConfirmationAndWritesOnlyAfterApprove() = runTest {
        val d = RecordingDisplay()
        val gate = FakeGate()
        val tool = GlassesShowTextTool(d, gate)
        assertThat(tool.spec.requiresConfirmation).isTrue()
        val r = tool.execute(buildJsonObject { put("text", "Hello G2") })
        assertThat(r).isInstanceOf(ToolResult.Success::class.java)
        assertThat(gate.asked).containsExactly("glasses_show_text")
        assertThat(d.shown.single()).isEqualTo("Jarvis" to "Hello G2")
    }

    @Test fun declinedWritesNothing() = runTest {
        val d = RecordingDisplay()
        val r = GlassesShowTextTool(d, FakeGate(approve = false)).execute(buildJsonObject { put("text", "x") })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(d.shown).isEmpty()
    }

    @Test fun unavailableFailsBeforeAsking() = runTest {
        val gate = FakeGate()
        val r = GlassesShowTextTool(RecordingDisplay("no permission"), gate).execute(buildJsonObject { put("text", "x") })
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(gate.asked).isEmpty()
    }

    @Test fun sanitizeStripsMarkdownAndTrims() {
        assertThat(GlassesText.sanitize("**Hi**\n\n  _there_ `x`", 100)).isEqualTo("Hi there x")
        assertThat(GlassesText.sanitize("a".repeat(500), GlassesText.MAX_TEXT)).hasLength(240)
    }

    @Test fun bleStubIsAlwaysUnavailable() {
        val ble = BleGlassesDisplay()
        assertThat(ble.unavailableReason()).isNotEmpty()
        assertThat(ble.show("t", "x")).isNotNull()
    }
}
