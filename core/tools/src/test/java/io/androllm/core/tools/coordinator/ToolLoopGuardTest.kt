package io.androllm.core.tools.coordinator

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ToolLoopGuardTest {

    private fun args(expression: String): JsonObject = buildJsonObject {
        put("expression", expression)
    }

    private fun success(): ToolResult = ToolResult.Success("ok")
    private fun failure(retryable: Boolean = true): ToolResult =
        ToolResult.Failure("boom", retryable = retryable)

    @Test
    fun `identical call with same arguments is never re-run`() {
        val guard = ToolLoopGuard()
        assertThat(guard.canExecute("calculate", args("23 * 48"))).isTrue()
        guard.record("calculate", args("23 * 48"), success())
        // Same tool + same args again → blocked (dedupe).
        assertThat(guard.canExecute("calculate", args("23 * 48"))).isFalse()
        assertThat(guard.blockedThisTurn).isTrue()
        // Same tool, DIFFERENT args → still allowed.
        assertThat(guard.canExecute("calculate", args("23 * 49"))).isTrue()
    }

    @Test
    fun `consecutive calls to the same tool are capped at two`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.canExecute("calculate", args("2 + 2"))).isTrue()
        guard.record("calculate", args("2 + 2"), success())
        // Third consecutive calculate → blocked.
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isFalse()
        // Interleaving another tool resets the consecutive counter.
        guard.record("search_web", buildJsonObject { put("query", "news") }, success())
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isTrue()
    }

    @Test
    fun `total calls are capped at five`() {
        val guard = ToolLoopGuard(maxTotalCalls = 5)
        repeat(5) { i ->
            assertThat(guard.canExecute("tool_$i", buildJsonObject { put("n", i) })).isTrue()
            guard.record("tool_$i", buildJsonObject { put("n", i) }, success())
        }
        assertThat(guard.canExecute("tool_6", buildJsonObject { put("n", 6) })).isFalse()
        assertThat(guard.stopReason()).contains("limit reached")
    }

    @Test
    fun `non-retryable failure blocks only that exact call - a corrected retry may run`() {
        // Step 5: app_open_action(package="Settings") failed "not in the App Atlas";
        // the model searched and retried with the real package — the guard blocked it.
        val guard = ToolLoopGuard()
        val wrong = buildJsonObject { put("package", "Settings"); put("action_id", "open") }
        val fixed = buildJsonObject { put("package", "com.android.settings"); put("action_id", "open") }
        guard.record("app_open_action", wrong, failure(retryable = false))
        guard.record("app_atlas_search", buildJsonObject { put("query", "settings") }, success())
        assertThat(guard.canExecute("app_open_action", wrong)).isFalse()
        assertThat(guard.canExecute("app_open_action", fixed)).isTrue()
        assertThat(guard.isDisabled("app_open_action")).isFalse()
    }

    @Test
    fun `argument-independent failures disable the whole tool`() {
        val guard = ToolLoopGuard()
        guard.record("send_sms", buildJsonObject { put("phone", "1") }, ToolResult.Failure("The user declined the action.", retryable = false))
        assertThat(guard.canExecute("send_sms", buildJsonObject { put("phone", "2") })).isFalse()
        val permission = ToolResult.Failure(
            "camera permission missing",
            data = buildJsonObject { put("status", "permission_denied") },
            retryable = false
        )
        guard.record("set_flashlight", buildJsonObject { put("on", true) }, permission)
        assertThat(guard.isDisabled("set_flashlight")).isTrue()
    }

    @Test
    fun `second failed call of a tool with other arguments disables it`() {
        val guard = ToolLoopGuard()
        guard.record("app_open_action", buildJsonObject { put("package", "a") }, failure(retryable = false))
        assertThat(guard.isDisabled("app_open_action")).isFalse()
        guard.record("app_open_action", buildJsonObject { put("package", "b") }, failure(retryable = false))
        assertThat(guard.isDisabled("app_open_action")).isTrue()
    }

    @Test
    fun `two failures of the same tool disable it`() {
        val guard = ToolLoopGuard()
        guard.record("search_web", buildJsonObject { put("query", "a") }, failure())
        guard.record("search_web", buildJsonObject { put("query", "b") }, failure())
        assertThat(guard.isDisabled("search_web")).isTrue()
    }

    @Test
    fun `successful calls do not disable the tool`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.isDisabled("calculate")).isFalse()
    }

    @Test
    fun `stopReason reports the consecutive-tool cap message`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        guard.record("calculate", args("2 + 2"), success())
        assertThat(guard.canExecute("calculate", args("3 + 3"))).isFalse()
        val reason = guard.stopReason()
        assertThat(reason).isNotNull()
        assertThat(reason).contains("calculate")
        assertThat(reason).contains("Continue reasoning without further tool calls")
    }

    @Test
    fun `no blocking means no stop reason`() {
        val guard = ToolLoopGuard()
        guard.record("calculate", args("1 + 1"), success())
        assertThat(guard.stopReason()).isNull()
    }
}
