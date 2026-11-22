package io.androllm.core.jarvistools.confirm

import com.google.common.truth.Truth.assertThat
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.ConfirmationMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ConfirmPolicyGateTest {

    private val spec = ToolSpec(name = "tesla_command", description = "d", requiresConfirmation = true,
        confirmationPrompt = "send the Tesla command {action}")
    private val args = buildJsonObject { put("action", "honk") }

    private fun gate(mode: ConfirmationMode, answer: Boolean, asked: MutableList<PendingToolConfirmation>) =
        ConfirmPolicyGate(
            executorConfirms = { AutomationSettings(confirmationMode = mode).shouldConfirm(it) },
            ask = { asked += it; answer }
        )

    @Test fun highRiskAndAlwaysModesRelyOnExecutorCard() = runTest {
        for (mode in listOf(ConfirmationMode.HIGH_RISK, ConfirmationMode.ALWAYS)) {
            val asked = mutableListOf<PendingToolConfirmation>()
            assertThat(gate(mode, false, asked).ensureConfirmed(spec, args)).isTrue()
            assertThat(asked).isEmpty() // no double prompt
        }
    }

    @Test fun neverModeStillAsks() = runTest {
        val asked = mutableListOf<PendingToolConfirmation>()
        assertThat(gate(ConfirmationMode.NEVER, true, asked).ensureConfirmed(spec, args)).isTrue()
        assertThat(asked).hasSize(1)
        assertThat(asked.single().speakableQuestion).contains("send the Tesla command honk")
    }

    @Test fun neverModeDeclineBlocks() = runTest {
        val asked = mutableListOf<PendingToolConfirmation>()
        assertThat(gate(ConfirmationMode.NEVER, false, asked).ensureConfirmed(spec, args)).isFalse()
    }

    @Test(expected = IllegalStateException::class)
    fun toolWithoutConfirmationFlagIsRejected() = runTest {
        gate(ConfirmationMode.NEVER, true, mutableListOf()).ensureConfirmed(spec.copy(requiresConfirmation = false), args)
    }
}
