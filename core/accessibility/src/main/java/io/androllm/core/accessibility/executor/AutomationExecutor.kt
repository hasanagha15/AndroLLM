package io.androllm.core.accessibility.executor

import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.RiskApproval
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.finder.UiSelector
import io.androllm.core.accessibility.planner.ActionPlanner
import io.androllm.core.accessibility.planner.HeuristicActionPlanner
import io.androllm.core.accessibility.planner.LlmActionPlanner
import io.androllm.core.accessibility.planner.PlannedAction
import io.androllm.core.accessibility.planner.SafetyClassifier
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Outcome of a full automation run. */
data class AutomationResult(
    val success: Boolean,
    val summary: String,
    val steps: Int,
    /** False when a step was refused by SensitiveAppGuard (retrying cannot help). */
    val retryable: Boolean = true
)

/** One executed plan step, recorded for the developer mode. */
data class ExecutedStep(val action: PlannedAction, val result: UiActionResult)

/**
 * The plan-act-verify loop behind `ui_run`:
 *
 * ```
 * read screen → planner picks ONE action → execute → verify (retry once)
 * ```
 *
 * - Step budget from settings guards against runaway loops.
 * - High-risk steps (send / pay / delete / install…) route through the shared
 *   [ToolConfirmationManager] — the chat card in chat mode, the spoken
 *   yes/no in voice mode.
 * - Cancellation is honoured between steps; a failed action retries once
 *   after a short settle, then the run reports honestly.
 */
@Singleton
class AutomationExecutor @Inject constructor(
    private val controller: AccessibilityController,
    private val settingsStore: AccessibilitySettingsStore,
    private val llmPlanner: LlmActionPlanner,
    private val heuristicPlanner: HeuristicActionPlanner,
    private val confirmationManager: ToolConfirmationManager,
    private val debug: AccessibilityDebugStore,
    /** Branch T: fixed waits scale with the phone's load factor k. */
    private val load: io.androllm.core.accessibility.timing.DeviceLoadMonitor =
        io.androllm.core.accessibility.timing.DeviceLoadMonitor.fixed()
) {

    /** Branch T: [baseMs] x k (1.0 ... 3.0). */
    private fun scaled(baseMs: Long): Long = (baseMs * load.loadFactor()).toLong()

    private val cancelled = AtomicBoolean(false)

    /** Cancels the in-flight run at the next step boundary. */
    fun cancel() {
        cancelled.set(true)
    }

    suspend fun run(goal: String, onStatus: suspend (String) -> Unit = {}): AutomationResult {
        cancelled.set(false)
        if (!controller.isConnected) {
            return AutomationResult(false, "The accessibility service is not enabled — turn it on in Settings → Accessibility → AndroLLM UI Automation.", 0)
        }
        val settings = settingsStore.current()
        val planner: ActionPlanner = if (settings.llmPlanning) llmPlanner else heuristicPlanner
        val history = mutableListOf<PlannedAction>()
        val steps = mutableListOf<ExecutedStep>()
        var lastAction: PlannedAction? = null

        for (step in 0 until settings.maxSteps) {
            if (cancelled.get()) {
                return AutomationResult(false, "The task was cancelled.", steps.size)
            }
            onStatus("Step ${step + 1} of ${settings.maxSteps}…")

            val screen = controller.readScreen()
            // K: a hidden screen (banking/payment, Tesla, WhatsApp) ends the run;
            // an unknown active window (app switching) is waited out like loading.
            screen.hiddenReason?.let {
                if (screen.hiddenRetryable) {
                    delay(scaled(600))
                    continue
                }
                return AutomationResult(false, it, steps.size, retryable = false)
            }
            // App transitions / loading spinners: wait, don't burn a step.
            if (screen.loading || (screen.isEmpty && step > 0)) {
                delay(scaled(600))
                continue
            }

            val action = runCatching { planner.nextAction(goal, screen, history) }.getOrNull()
                ?: runCatching { heuristicPlanner.nextAction(goal, screen, history) }.getOrNull()
            if (action == null) {
                return AutomationResult(false, "Could not plan the next step for this screen.", steps.size)
            }
            // Loop guard: the same action twice in a row means the screen did
            // not change — stop instead of hammering the UI. Waits are exempt:
            // a slow app can legitimately need repeated waits.
            if (action != lastAction || action is PlannedAction.Wait) {
                lastAction = action
            } else {
                return AutomationResult(false, "Stuck on '${action.description}' — the screen did not change.", steps.size)
            }
            history += action

            when (action) {
                is PlannedAction.Done -> {
                    val summary = action.summary.ifBlank { "Task complete." }
                    debug.record("executor", "done in ${steps.size} step(s): $summary")
                    return AutomationResult(true, summary, steps.size)
                }

                is PlannedAction.Wait -> {
                    delay(action.millis.coerceIn(0, 10_000))
                    steps += ExecutedStep(action, UiActionResult.ok("waited"))
                }

                is PlannedAction.RequestConfirmation -> {
                    val approved = confirm(action.summary)
                    if (!approved) return AutomationResult(false, "The user declined: ${action.summary}", steps.size)
                }

                is PlannedAction.LaunchApp -> {
                    val result = controller.launchApp(action.label)
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message, steps.size, result.retryable)
                }

                is PlannedAction.Click -> {
                    // Branch M: a risky element (send / pay / confirm / delete, en/ru/az)
                    // ALWAYS asks with a tap-only card; the risky-goal heuristic
                    // stays under the owner's "confirm high-risk" setting.
                    val riskyTarget = SafetyClassifier.isRiskyTarget(action.target)
                    if (riskyTarget || (settings.confirmHighRisk && SafetyClassifier.requiresConfirmation(goal, action))) {
                        val approved = confirm(describeTap(action.target, action.index), tapOnly = riskyTarget)
                        if (!approved) return AutomationResult(false, "The user declined the action.", steps.size)
                    }
                    val selector = UiSelector(textContains = action.target, index = action.index)
                    var approval = if (riskyTarget) RiskApproval(action.target) else null
                    var result = controller.click(selector, approval = approval)
                    if (!result.success && result.retryable) {
                        delay(scaled(500)) // let the UI settle, then one retry
                        result = controller.click(selector, approval = approval)
                    }
                    // Branch M (gate B1): the element actually found is risky ("Отпра" →
                    // «Отправить»): ask with ITS full label, then tap only that element.
                    result.risky?.let { refusal ->
                        val approved = confirm(describeTap(refusal.element, action.index), tapOnly = true)
                        if (!approved) return AutomationResult(false, "The user declined the action.", steps.size)
                        approval = RiskApproval(refusal.element, refusal.id)
                        result = controller.click(selector, approval = approval)
                    }
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message, steps.size, result.retryable)
                }

                is PlannedAction.Type -> {
                    // Branch M: text with a line break can submit — tap-only card first.
                    if (SafetyClassifier.isSubmittingText(action.text)) {
                        val approved = confirm("type text that ends a line (it can send it)", tapOnly = true)
                        if (!approved) return AutomationResult(false, "The user declined the action.", steps.size)
                    }
                    val result = controller.type(action.text, action.into)
                    steps += ExecutedStep(action, result)
                    if (!result.success) return AutomationResult(false, result.message, steps.size, result.retryable)
                }

                is PlannedAction.Scroll -> {
                    val result = controller.scroll(action.direction)
                    steps += ExecutedStep(action, result)
                }

                is PlannedAction.Swipe -> {
                    val result = controller.swipe(action.direction)
                    steps += ExecutedStep(action, result)
                }

                is PlannedAction.Back -> executeNav(action, steps, "back")
                is PlannedAction.Home -> executeNav(action, steps, "home")
                is PlannedAction.Recents -> executeNav(action, steps, "recents")
                is PlannedAction.Notifications -> executeNav(action, steps, "notifications")
                is PlannedAction.QuickSettings -> executeNav(action, steps, "quick_settings")
            }
            onStatus("Step ${step + 1}: ${action.description} done")
            debug.record("executor", "step ${steps.size}: ${action.description} → ${steps.last().result.message}")
        }
        return AutomationResult(false, "Reached the step limit (${settings.maxSteps}) before finishing the task.", steps.size)
    }

    private suspend fun executeNav(
        action: PlannedAction,
        steps: MutableList<ExecutedStep>,
        name: String
    ) {
        val result = controller.navigate(name)
        steps += ExecutedStep(action, result)
    }

    private fun describeTap(label: String, index: Int): String =
        "tap \"${label.take(160)}\"" + if (index > 0) " (match #${index + 1} on screen)" else ""

    /** Asks the user (chat card or spoken voice confirmation). */
    private suspend fun confirm(summary: String, tapOnly: Boolean = false): Boolean {
        val question = "proceed with: $summary"
        val confirmation = PendingToolConfirmation(
            id = "ui_${System.currentTimeMillis()}_${summary.hashCode().toUInt().toString(16)}",
            toolName = "ui_automation",
            toolDisplayName = "UI Automation",
            actionSummary = question,
            speakableQuestion = if (tapOnly) {
                "Please confirm on the screen: $summary."
            } else {
                "Do you want me to $summary? Say yes to confirm, or no to cancel."
            },
            tapOnly = tapOnly
        )
        return withTimeoutOrNull(300_000L) { confirmationManager.awaitDecision(confirmation) } ?: false
    }
}
