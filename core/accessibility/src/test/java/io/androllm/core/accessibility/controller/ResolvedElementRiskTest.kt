package io.androllm.core.accessibility.controller

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.controller.RiskyElementGate.Decision
import io.androllm.core.accessibility.controller.RiskyElementGate.Facts
import io.androllm.core.accessibility.executor.AutomationExecutor
import io.androllm.core.accessibility.planner.HeuristicActionPlanner
import io.androllm.core.accessibility.planner.LlmActionPlanner
import io.androllm.core.accessibility.planner.PlannedAction
import io.androllm.core.accessibility.planner.SafetyClassifier
import io.androllm.core.accessibility.settings.AccessibilitySettings
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.accessibility.tools.UiClickTool
import io.androllm.core.accessibility.tools.UiGestureTool
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

/**
 * Branch M gate B1: the risk decision is made on the RESOLVED element, so a
 * partial query ("Отпра", "öndər", "onfir") never taps «Отправить» / «Göndər» /
 * «Confirm» unconfirmed; B2: calls, posts, grants, signatures… are risky too.
 */
class ResolvedElementRiskTest {

    private fun ch(code: Int) = code.toChar().toString()

    // ── pure gate ────────────────────────────────────────────────────────

    @Test
    fun `partial queries resolve to risky elements and are refused without approval`() {
        listOf("Отправить", "Göndər", "Confirm", "Delete", "Оплатить").forEach { label ->
            val d = RiskyElementGate.decide(Facts(text = label), null)
            assertWithMessage(label).that(d).isEqualTo(Decision.Refuse(label))
        }
        // An approval for the substring the model typed does not cover the full element.
        assertThat(RiskyElementGate.decide(Facts(text = "Отправить"), RiskApproval("Отпра")))
            .isInstanceOf(Decision.Refuse::class.java)
        assertThat(RiskyElementGate.decide(Facts(text = "Отправить"), RiskApproval("отправить")))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `index mismatch - approval for Send does not cover Send 500 AZN to Ali`() {
        val d = RiskyElementGate.decide(Facts(text = "Send 500 AZN to Ali"), RiskApproval("Send"))
        assertThat(d).isEqualTo(Decision.Refuse("Send 500 AZN to Ali"))
        assertThat(RiskyElementGate.refusalMessage(d as Decision.Refuse)).contains("label='Send 500 AZN to Ali'")
    }

    @Test
    fun `long content descriptions are checked in full`() {
        val desc = "Send message to Mammadov Ali Huseyn oglu in the family group chat right now"
        assertThat(desc.length).isGreaterThan(48)
        assertThat(RiskyElementGate.decide(Facts(description = desc), null)).isEqualTo(Decision.Refuse(desc))
    }

    @Test
    fun `clickable ancestor and view id count`() {
        val child = Facts(text = "Ali", ancestors = listOf(Facts(description = "Позвонить Ali")))
        assertThat(RiskyElementGate.decide(child, null)).isEqualTo(Decision.Refuse("Позвонить Ali"))
        assertThat(RiskyElementGate.decide(child, RiskApproval("Позвонить Ali"))).isEqualTo(Decision.Allow)

        val idOnly = Facts(text = "Next", viewId = "com.example:id/send_button")
        val refuse = RiskyElementGate.decide(idOnly, null) as Decision.Refuse
        assertThat(refuse.id).isEqualTo("send_button")
        assertThat(RiskyElementGate.refusalMessage(refuse)).contains("id='send_button'")
        assertThat(RiskyElementGate.decide(idOnly, RiskApproval("Next", "send_button"))).isEqualTo(Decision.Allow)

        assertThat(RiskyElementGate.decide(Facts(text = "Search", viewId = "com.x:id/search_button"), null))
            .isEqualTo(Decision.Allow)
        assertThat(RiskyElementGate.displayLabel(Facts(viewId = "com.x:id/pay_btn"))).isEqualTo("pay_btn")
    }

    // ── B2 vocabulary + folding ──────────────────────────────────────────

    @Test
    fun `calls, posts, forwards, grants, consent and signatures are risky in en ru az tr`() {
        listOf(
            // en
            "Call", "Voice call", "Dial", "Post", "Publish", "Share", "Forward", "Tweet", "Allow", "Grant access",
            "Accept", "I agree", "Sign", "Subscribe", "Buy now", "Purchase", "Checkout", "Transfer",
            // ru
            "Позвонить", "Вызов", "Переслать", "Поделиться", "Опубликовать", "Послать", "Разрешить", "Принять",
            "Согласиться", "Подписать", "Оформить", "Купить", "Перевести",
            // az
            "Zəng et", "Paylaş", "Yolla", "Göndər", "İcazə ver", "Qəbul et", "Razıyam", "İmzala", "Al", "Sifariş et",
            "Köçür", "Ödə",
            // tr
            "Gönder", "Onayla", "İzin ver", "Kabul et", "Sipariş ver", "Abone ol"
        ).forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isTrue() }
    }

    @Test
    fun `benign labels stay benign`() {
        listOf("Search", "Recall", "Design", "Dialog", "Albom", "Silindr", "Settings", "Настройки", "Axtar", "Back", "Page 2", "25")
            .forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isFalse() }
    }

    @Test
    fun `diacritics, homoglyphs and zero-width characters are folded`() {
        val cyrillicSchwa = "Gönd" + ch(0x4D9) + "r"             // Göndәr with Cyrillic ә
        val cyrillicE = "S" + ch(0x435) + "nd"                     // Sеnd with Cyrillic е
        val latinO = "O" + "тправить"                               // Latin O + Cyrillic rest
        val zeroWidth = "Se" + ch(0x200B) + "nd"
        val softHyphen = "Оп" + ch(0xAD) + "латить"
        listOf(cyrillicSchwa, cyrillicE, latinO, zeroWidth, softHyphen, "GÖNDƏR", "TƏSDİQLƏ")
            .forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isTrue() }
    }

    @Test
    fun `amount-only buttons and slide-to-pay controls are risky`() {
        listOf(ch(0x20BC) + " 25,00", "AZN 20", "$4.99", "25 " + ch(0x20BD), "10 manat")
            .forEach { assertWithMessage(it).that(SafetyClassifier.isRiskyTarget(it)).isTrue() }
        assertThat(SafetyClassifier.isSlideToConfirm("Slide to pay")).isTrue()
        assertThat(SafetyClassifier.isSlideToConfirm("Проведите, чтобы оплатить")).isTrue()
        assertThat(SafetyClassifier.isSlideToConfirm("Slide to unlock")).isFalse()
    }

    // ── tools / executor wiring ──────────────────────────────────────────

    private fun args(vararg kv: Pair<String, String>) = buildJsonObject { kv.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    @Test
    fun `ui_click with a truncated label is refused with the full element label and no approval`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        val approvalSeen = slot<RiskApproval>()
        // Later stubs win: the null-approval stub must come after the capturing one.
        coEvery { c.click(any(), any(), capture(approvalSeen)) } returns UiActionResult.ok("Tapped.")
        coEvery { c.click(any(), any(), isNull()) } returns UiActionResult.risky(Decision.Refuse("Отправить"))
        val tool = UiClickTool(c)

        // "Отпра" is not risky by itself → no card; the controller refuses on the resolved element.
        assertThat(tool.riskyAction(args("label" to "Отпра"))).isNull()
        val r = tool.execute(args("label" to "Отпра"))
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat((r as ToolResult.Failure).retryable).isFalse()
        assertThat(r.summary).contains("label='Отправить'")

        // Retry with the full label → card (riskyAction) → approved → approval names the element.
        assertThat(tool.riskyAction(args("label" to "Отправить"))).contains("Отправить")
        val ok = withContext(RiskyActionApproval("c2")) { tool.execute(args("label" to "Отправить")) }
        assertThat(ok.isSuccess).isTrue()
        assertThat(approvalSeen.captured).isEqualTo(RiskApproval("Отправить", null))
    }

    @Test
    fun `card describes the match index`() {
        val tool = UiClickTool(mockk(relaxed = true))
        assertThat(tool.riskyAction(args("label" to "Send", "index" to "2"))).contains("match #3")
    }

    @Test
    fun `ui_gesture on a truncated risky label is refused`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.longClick(any(), isNull()) } returns UiActionResult.risky(Decision.Refuse("Delete"), verb = "ui_gesture")
        val r = UiGestureTool(c).execute(args("action" to "long_press", "label" to "elet"))
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat((r as ToolResult.Failure).summary).contains("label='Delete'")
    }

    private fun executorWith(c: AccessibilityController, manager: ToolConfirmationManager, target: String): AutomationExecutor {
        coEvery { c.readScreen() } returns UiScreenSnapshot(packageName = "com.example.chat")
        val settings = mockk<AccessibilitySettingsStore>()
        coEvery { settings.current() } returns AccessibilitySettings(llmPlanning = false, maxSteps = 2)
        val heuristic = mockk<HeuristicActionPlanner>()
        coEvery { heuristic.nextAction(any(), any(), any()) } returnsMany listOf(PlannedAction.Click(target), PlannedAction.Done("ok"))
        return AutomationExecutor(c, settings, mockk<LlmActionPlanner>(relaxed = true), heuristic, manager, mockk(relaxed = true))
    }

    @Test
    fun `ui_run step with a partial label asks with the full element label and taps only after approval`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        val approval = slot<RiskApproval>()
        coEvery { c.click(any(), any(), capture(approval)) } returns UiActionResult.ok("Tapped.")
        coEvery { c.click(any(), any(), isNull()) } returns UiActionResult.risky(Decision.Refuse("Göndər"))
        val manager = mockk<ToolConfirmationManager>()
        val card = slot<PendingToolConfirmation>()
        coEvery { manager.awaitDecision(capture(card)) } returns true

        executorWith(c, manager, "öndər").run("write to Ali")

        // (The empty test snapshot then waits out the step budget; only the tap matters here.)
        coVerify(exactly = 2) { c.click(any(), any(), any()) }
        assertThat(card.captured.tapOnly).isTrue()
        assertThat(card.captured.actionSummary).contains("Göndər")
        assertThat(approval.captured).isEqualTo(RiskApproval("Göndər", null))
    }

    @Test
    fun `ui_run step declined on the resolved element never taps`() = runTest {
        val c = mockk<AccessibilityController>()
        every { c.isConnected } returns true
        coEvery { c.click(any(), any(), isNull()) } returns UiActionResult.risky(Decision.Refuse("Confirm"))
        val manager = mockk<ToolConfirmationManager>()
        coEvery { manager.awaitDecision(any()) } returns false

        val result = executorWith(c, manager, "onfir").run("finish")

        assertThat(result.success).isFalse()
        coVerify(exactly = 1) { c.click(any(), any(), any()) } // the refused probe only
    }
}
