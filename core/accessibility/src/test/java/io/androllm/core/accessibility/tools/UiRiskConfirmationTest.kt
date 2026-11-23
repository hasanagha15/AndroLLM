package io.androllm.core.accessibility.tools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.accessibility.executor.AutomationExecutor
import io.androllm.core.accessibility.planner.HeuristicActionPlanner
import io.androllm.core.accessibility.planner.LlmActionPlanner
import io.androllm.core.accessibility.planner.PlannedAction
import io.androllm.core.accessibility.planner.SafetyClassifier
import io.androllm.core.accessibility.settings.AccessibilitySettings
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.RiskyActionApproval
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** Branch M: risky UI targets (en / ru / az) never run without a tap-only confirmation. */
class UiRiskConfirmationTest {

    private val NL = 10.toChar().toString()

    private fun controller(): AccessibilityController {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.click(any(), any()) } returns UiActionResult.ok("Tapped.")
        coEvery { c.type(any(), any()) } returns UiActionResult.ok("Typed.")
        coEvery { c.longClick(any()) } returns UiActionResult.ok("Long-pressed.")
        coEvery { c.doubleTap(any()) } returns UiActionResult.ok("Double-tapped.")
        return c
    }

    private fun args(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    // ── classifier ───────────────────────────────────────────────────────

    @Test
    fun `risky labels in english, russian and azerbaijani`() {
        listOf(
            "Send", "Pay", "Confirm", "Delete", "Order", "Place order", "Buy now", "Transfer",
            "Отправить", "ОТПРАВИТЬ", "Оплатить", "Подтвердить", "Удалить", "Заказать", "Купить", "Перевести",
            "Göndər", "GÖNDƏR", "Gonder", "Ödə", "Ödəniş et", "Təsdiqlə", "TƏSDİQLƏ", "Sil", "Satın al", "Sifariş et",
            "send_button", "btn_pay"
        ).forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isTrue() }
    }

    @Test
    fun `benign labels stay benign`() {
        listOf("Search", "Поиск", "Axtar", "Settings", "Настройки", "Albom", "Silindr", "Back", "Назад", "Geri", "Go")
            .forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isFalse() }
    }

    @Test
    fun `submitting text and gestures`() {
        assertThat(UiRisk.typeRisk("hello")).isNull()
        assertThat(UiRisk.typeRisk("hello\n")).isNotNull()
        assertThat(UiRisk.gestureRisk("long_press", "Удалить")).isNotNull()
        assertThat(UiRisk.gestureRisk("drag", "Slide to pay")).isNotNull()
        assertThat(UiRisk.gestureRisk("long_press", "Photo")).isNull()
        assertThat(UiRisk.gestureRisk("pinch_in", "Send")).isNull()
        assertThat(UiRisk.clickRisk("Search", "search_button")).isNull()
        assertThat(UiRisk.clickRisk("Next", "send_button")).isNotNull()
    }

    // ── tools: riskyAction drives the executor card, execute refuses without approval ──

    @Test
    fun `ui_click on a risky label declares a risky action and refuses unapproved`() = runTest {
        val c = controller()
        val tool = UiClickTool(c)
        listOf("Send", "Отправить", "Göndər", "Pay", "Confirm", "Delete", "Order").forEach { label ->
            assertWithMessage(label).that(tool.riskyAction(args("label" to label))).isNotNull()
            val r = tool.execute(args("label" to label))
            assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
            assertThat((r as ToolResult.Failure).retryable).isFalse()
            assertThat(r.summary).isEqualTo(UiRisk.UNCONFIRMED)
        }
        coVerify(exactly = 0) { c.click(any(), any()) }
    }

    @Test
    fun `ui_click runs after the card was approved, including the atlas id path`() = runTest {
        val c = controller()
        val tool = UiClickTool(c)
        val r1 = withContext(RiskyActionApproval("call-1")) { tool.execute(args("label" to "Отправить")) }
        assertThat(r1.isSuccess).isTrue()
        val r2 = withContext(RiskyActionApproval("call-2")) { tool.execute(args("label" to "Send", "id" to "send_button")) }
        assertThat(r2.isSuccess).isTrue()
        coVerify(exactly = 2) { c.click(any(), any()) }
    }

    @Test
    fun `benign ui_click needs no approval`() = runTest {
        val c = controller()
        val tool = UiClickTool(c)
        assertThat(tool.riskyAction(args("label" to "Search"))).isNull()
        assertThat(tool.execute(args("label" to "Search")).isSuccess).isTrue()
    }

    @Test
    fun `ui_type with enter and ui_gesture on a risky label need approval`() = runTest {
        val c = controller()
        val type = UiTypeTool(c)
        // ToolArgs trims, so only an INNER line break survives to the field.
        val multi = "hi there" + NL + "second line"
        assertThat(type.riskyAction(args("text" to multi))).isNotNull()
        assertThat(type.execute(args("text" to multi))).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(type.execute(args("text" to "hi there")).isSuccess).isTrue()
        assertThat(withContext(RiskyActionApproval("t")) { type.execute(args("text" to multi)) }.isSuccess).isTrue()

        val gesture = UiGestureTool(c)
        assertThat(gesture.riskyAction(args("action" to "long_press", "label" to "Sil"))).isNotNull()
        assertThat(gesture.execute(args("action" to "long_press", "label" to "Sil"))).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(gesture.execute(args("action" to "long_press", "label" to "Photo")).isSuccess).isTrue()
        coVerify(exactly = 1) { c.longClick(any()) }
    }

    // ── ui_run steps ────────────────────────────────────────────────────

    // Step 0 plans even on an "empty" snapshot (only later steps wait it out).
    private fun screen(): UiScreenSnapshot = UiScreenSnapshot(packageName = "com.example.chat")

    @Test
    fun `ui_run risky step asks with a tap-only card even when confirmHighRisk is off`() = runTest {
        val c = controller()
        coEvery { c.readScreen() } returns screen()
        val settings = mockk<AccessibilitySettingsStore>()
        coEvery { settings.current() } returns AccessibilitySettings(confirmHighRisk = false, llmPlanning = false)
        val heuristic = mockk<HeuristicActionPlanner>()
        coEvery { heuristic.nextAction(any(), any(), any()) } returns PlannedAction.Click("Göndər")
        val manager = mockk<ToolConfirmationManager>()
        val card = slot<PendingToolConfirmation>()
        coEvery { manager.awaitDecision(capture(card)) } returns false

        val result = AutomationExecutor(c, settings, mockk<LlmActionPlanner>(relaxed = true), heuristic, manager, mockk(relaxed = true))
            .run("write to Ali")

        assertThat(result.success).isFalse()
        assertThat(card.captured.tapOnly).isTrue()
        assertThat(ToolConfirmationManager.isTapOnly(card.captured)).isTrue()
        coVerify(exactly = 0) { c.click(any(), any()) }
    }
}
