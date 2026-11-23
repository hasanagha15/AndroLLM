package io.androllm.core.accessibility.executor

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.accessibility.planner.HeuristicActionPlanner
import io.androllm.core.accessibility.planner.LlmActionPlanner
import io.androllm.core.accessibility.settings.AccessibilitySettings
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** K: ui_run stops at once on a hidden (banking/payment, Tesla, WhatsApp) screen; no planner sees it. */
class HiddenScreenExecutorTest {

    @Test
    fun `hidden screen ends the run as non-retryable without planning`() = runTest {
        val bank = SensitiveAppGuard.PAYMENT_PACKAGES.first()
        val controller = mockk<AccessibilityController>()
        every { controller.isConnected } returns true
        coEvery { controller.readScreen() } returns
            UiScreenSnapshot.hidden(bank, SensitiveAppGuard.screenReadBlockedReason(bank)!!)
        val settings = mockk<AccessibilitySettingsStore>()
        coEvery { settings.current() } returns AccessibilitySettings()
        val llm = mockk<LlmActionPlanner>(relaxed = true)
        val heuristic = mockk<HeuristicActionPlanner>(relaxed = true)

        val result = AutomationExecutor(controller, settings, llm, heuristic, mockk(relaxed = true), mockk(relaxed = true))
            .run("check my balance")

        assertThat(result.success).isFalse()
        assertThat(result.retryable).isFalse()
        assertThat(result.summary).contains("banking/payment")
        coVerify(exactly = 0) { llm.nextAction(any(), any(), any()) }
        coVerify(exactly = 0) { heuristic.nextAction(any(), any(), any()) }
    }
}
