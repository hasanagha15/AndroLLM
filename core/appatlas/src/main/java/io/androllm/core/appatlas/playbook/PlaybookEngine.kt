package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.NodeRef
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.controller.RiskApproval
import io.androllm.core.accessibility.controller.RiskyElementGate
import io.androllm.core.accessibility.controller.ScreenNode
import io.androllm.core.accessibility.controller.UiActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the replay engine needs from the device. Production: the
 * accessibility controller ([AccessibilityPlaybookDriver]); tests: a fake.
 */
interface PlaybookDriver {
    /** Visible nodes of the active window (tree only — never a screenshot); null = service off. */
    suspend fun snapshot(): NodeSnapshot?
    /** Ticks on TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED; null = poll only. */
    val uiEvents: StateFlow<Long>?
    suspend fun tap(ref: NodeRef, approval: RiskApproval?): UiActionResult
    /** [ref] null = focused / first editable field. The text is never logged. */
    suspend fun type(ref: NodeRef?, text: String): UiActionResult
    suspend fun scroll(direction: String): UiActionResult
    suspend fun back(): UiActionResult
    /** Opens [uri] in [pkg] only (setPackage); null = ok, else the refusal. */
    suspend fun openDeeplink(pkg: String, uri: String): String?
    /** Brings [pkg] to the front via its launcher intent; null = ok, else the refusal. */
    suspend fun launch(pkg: String): String?
    /** Tap-only confirmation card (ToolConfirmationManager). True = approved by a tap. */
    suspend fun confirmTap(summary: String): Boolean
}

/** Why a step stopped. Mismatch kinds make the playbook stale for that app version. */
enum class FailureKind(val wire: String, val mismatch: Boolean) {
    EXPECT_TIMEOUT("expect_timeout", true),
    TARGET_NOT_FOUND("target_not_found", true),
    WRONG_APP("wrong_app", true),
    AMBIGUOUS("ambiguous_target", true),
    ACTION_FAILED("action_failed", false),
    DECLINED("declined", false),
    BLOCKED("blocked", false),
    SERVICE_OFF("service_off", false),
    URI_REFUSED("uri_refused", false),
    SLOT_MISSING("slot_missing", false)
}

/** Structured failure: step index, what was expected, what was seen — ids only, never screen or user text. */
data class StepFailure(
    val stepIndex: Int,
    val op: String,
    val kind: FailureKind,
    val expectedIds: List<String> = emptyList(),
    val expectedTexts: List<String> = emptyList(),
    val seenIds: List<String> = emptyList(),
    val seenPackage: String? = null,
    val bestScore: Double? = null
) {
    /** One line for the model / Jarvis. Expected texts are the playbook's own app UI strings. */
    fun describe(): String = buildString {
        append("step ").append(stepIndex).append(" (").append(op).append("): ").append(kind.wire)
        if (expectedIds.isNotEmpty()) append("; expected ids ").append(expectedIds.joinToString(",") { "#$it" })
        if (expectedTexts.isNotEmpty()) append("; expected strings ").append(expectedTexts.size)
        if (seenPackage != null) append("; seen app ").append(seenPackage)
        if (seenIds.isNotEmpty()) append("; seen ids ").append(seenIds.joinToString(",") { "#$it" })
        bestScore?.let { append("; best locator score ").append("%.2f".format(java.util.Locale.ROOT, it)) }
    }
}

data class PlaybookRunResult(
    val success: Boolean,
    val stepsDone: Int,
    /** True only when the playbook has a postcondition and it was seen after the last step. */
    val verified: Boolean = false,
    val hasPostcondition: Boolean = false,
    val failure: StepFailure? = null,
    /** "<step>:<kind+kind>" of the locators that matched (self-repair stats, ids only). */
    val locatorStats: List<String> = emptyList(),
    val elapsedMs: Long = 0L
)

/**
 * Deterministic replay of one playbook through the accessibility controller.
 * No LLM call, no screenshot:
 * - before each action the expected screen is awaited (event-driven: wakes on
 *   window-state / window-content events with a short debounce, polls every
 *   [POLL_MS] as a fallback) up to the step's timeout;
 * - the foreground app must be the playbook's package;
 * - targets resolve through weighted locators ([LocatorResolver]);
 * - the confirmation gate runs before a risky tap, decided on the RESOLVED
 *   element (branch M's RiskyElementGate inside the controller); a declared
 *   risky step asks even when the gate would not;
 * - on mismatch it stops with a [StepFailure] (ids only).
 * [strict] (unverified app version): every tap / type needs its target
 * present before acting, semantic locators, a unique match, no scrolling.
 */
class PlaybookEngine(
    private val driver: PlaybookDriver,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun run(playbook: Playbook, slots: SlotValues, strict: Boolean): PlaybookRunResult {
        val start = clock()
        val stats = mutableListOf<String>()
        fun fail(f: StepFailure, done: Int) = PlaybookRunResult(
            false, done, hasPostcondition = playbook.postcondition != null, failure = f,
            locatorStats = stats, elapsedMs = clock() - start
        )

        val first = driver.snapshot() ?: return fail(StepFailure(0, playbook.steps.first().op, FailureKind.SERVICE_OFF), 0)
        if (first.blockedReason != null) return fail(StepFailure(0, playbook.steps.first().op, FailureKind.BLOCKED, seenPackage = first.packageName), 0)
        if (first.packageName != playbook.packageName && playbook.steps.first().op != "open_deeplink") {
            driver.launch(playbook.packageName)?.let {
                return fail(StepFailure(0, playbook.steps.first().op, FailureKind.ACTION_FAILED, seenPackage = first.packageName), 0)
            }
        }

        for ((i, step) in playbook.steps.withIndex()) {
            val minScore = step.minScore ?: Locator.DEFAULT_MIN_SCORE
            val needsApp = step.op != "open_deeplink" && step.op != "wait"
            val needsTarget = step.op == "tap" || (step.op == "type" && step.locators.isNotEmpty())

            // 1. Expected screen (+ the right app, + in strict mode the target itself).
            var resolution: Resolution? = null
            if (needsApp || step.expect != null) {
                val snap = awaitScreen(step.timeoutMs) { s ->
                    (!needsApp || s.packageName == playbook.packageName) &&
                        (step.expect == null || expectSatisfied(step.expect, s.nodes)) &&
                        (!(strict && needsTarget) || resolve(step, s.nodes, minScore, strict) != null)
                }
                if (snap == null || snap.blockedReason != null) {
                    val last = driver.snapshot()
                    if (last == null) return fail(StepFailure(i, step.op, FailureKind.SERVICE_OFF), i)
                    if (last.blockedReason != null) return fail(StepFailure(i, step.op, FailureKind.BLOCKED, seenPackage = last.packageName), i)
                    val kind = when {
                        needsApp && last.packageName != playbook.packageName -> FailureKind.WRONG_APP
                        step.expect != null && !expectSatisfied(step.expect, last.nodes) -> FailureKind.EXPECT_TIMEOUT
                        else -> if (resolve(step, last.nodes, minScore, false) != null) FailureKind.AMBIGUOUS else FailureKind.TARGET_NOT_FOUND
                    }
                    return fail(mismatch(i, step, kind, last, strict), i)
                }
                if (needsTarget) resolution = resolve(step, snap.nodes, minScore, strict)
            }

            // 2. Act.
            val result: UiActionResult = when (step.op) {
                "tap", "type" -> {
                    if (needsTarget && resolution == null) {
                        // Target may still be appearing: wait for it within the step timeout.
                        val snap = awaitScreen(step.timeoutMs) { s -> resolve(step, s.nodes, minScore, strict) != null }
                        resolution = snap?.let { resolve(step, it.nodes, minScore, strict) }
                        if (resolution == null) {
                            val last = driver.snapshot() ?: return fail(StepFailure(i, step.op, FailureKind.SERVICE_OFF), i)
                            return fail(mismatch(i, step, FailureKind.TARGET_NOT_FOUND, last, strict), i)
                        }
                    }
                    resolution?.let { stats += "$i:${it.matched.joinToString("+")}" }
                    if (step.op == "tap") {
                        when (val r = tapWithGate(step, resolution!!)) {
                            null -> return fail(StepFailure(i, step.op, FailureKind.DECLINED), i)
                            else -> r
                        }
                    } else {
                        val text = PlaybookSlots.renderText(step.text.orEmpty(), slots)
                            ?: return fail(StepFailure(i, step.op, FailureKind.SLOT_MISSING), i)
                        val ref = resolution?.node?.let(::refOf)
                        driver.type(ref, text).let { r ->
                            if (!r.success && r.retryable) { settle(); driver.type(ref, text) } else r
                        }
                    }
                }
                "scroll" -> driver.scroll(step.direction ?: "down")
                "wait" -> { delay(step.waitMs ?: 0L); UiActionResult.ok("waited") }
                "back" -> driver.back()
                "open_deeplink" -> {
                    val uri = PlaybookSlots.renderUri(step.uri.orEmpty(), slots)
                        ?: return fail(StepFailure(i, step.op, FailureKind.URI_REFUSED), i)
                    driver.openDeeplink(playbook.packageName, uri)?.let {
                        return fail(StepFailure(i, step.op, FailureKind.URI_REFUSED), i)
                    }
                    UiActionResult.ok("opened")
                }
                else -> return fail(StepFailure(i, step.op, FailureKind.ACTION_FAILED), i)
            }
            if (!result.success) {
                val kind = if (!result.retryable && result.risky == null) FailureKind.BLOCKED else FailureKind.ACTION_FAILED
                return fail(StepFailure(i, step.op, kind, seenPackage = driver.snapshot()?.packageName), i)
            }
            settle()
        }

        // 3. Cheap verifier.
        val post = playbook.postcondition
        val verified = if (post == null) false else {
            awaitScreen(POSTCONDITION_TIMEOUT_MS) { s -> s.packageName == playbook.packageName && expectSatisfied(post, s.nodes) } != null
        }
        return PlaybookRunResult(
            success = true, stepsDone = playbook.steps.size, verified = verified, hasPostcondition = post != null,
            locatorStats = stats, elapsedMs = clock() - start
        )
    }

    /** Taps with the confirmation gate. Null = the owner declined. */
    private suspend fun tapWithGate(step: PlaybookStep, res: Resolution): UiActionResult? {
        val ref = refOf(res.node)
        val facts = RiskyElementGate.Facts(res.node.text, res.node.description, res.node.viewId)
        val label = RiskyElementGate.displayLabel(facts)
        var approval: RiskApproval? = null
        if (step.risky) {
            // Declared / computed risky step: ask on a tap-only card, naming the resolved element.
            if (!driver.confirmTap(describe(label, res.node))) return null
            approval = RiskApproval(label, res.node.idEntry)
        }
        var r = driver.tap(ref, approval)
        r.risky?.let { refusal ->
            // Branch M gate: the resolved element (or its clickable ancestor) is risky.
            if (!driver.confirmTap(describe(refusal.element, res.node))) return null
            r = driver.tap(ref, RiskApproval(refusal.element, refusal.id))
        }
        if (!r.success && r.retryable && r.risky == null) {
            settle()
            r = driver.tap(ref, approval)
        }
        return r
    }

    private fun describe(label: String, node: ScreenNode): String =
        "tap \"${label.take(160)}\"" + (node.idEntry?.let { " (#$it)" } ?: "") + " — playbook step"

    private fun resolve(step: PlaybookStep, nodes: List<ScreenNode>, minScore: Double, strict: Boolean): Resolution? =
        LocatorResolver.resolve(step.locators, nodes, minScore, strict, preferEditable = step.op == "type")

    private fun mismatch(i: Int, step: PlaybookStep, kind: FailureKind, snap: NodeSnapshot, strict: Boolean) = StepFailure(
        stepIndex = i,
        op = step.op,
        kind = kind,
        expectedIds = (step.expect?.ids.orEmpty() + step.locators.filter { it.kind == Locator.ID }.mapNotNull { it.value }).distinct().take(8),
        expectedTexts = step.expect?.texts.orEmpty(),
        seenIds = seenIds(snap),
        seenPackage = snap.packageName,
        bestScore = if (step.locators.isNotEmpty()) LocatorResolver.bestScore(step.locators, snap.nodes) else null
    )

    /**
     * Polls the tree until [cond] holds or [timeoutMs] passes. Wakes on UI
     * events (debounced) and at least every [POLL_MS]. Null = timed out.
     */
    private suspend fun awaitScreen(timeoutMs: Long, cond: (NodeSnapshot) -> Boolean): NodeSnapshot? {
        var last: NodeSnapshot? = null
        val ok = withTimeoutOrNull(timeoutMs) {
            var res: Boolean? = null
            while (res == null) {
                val tick = driver.uiEvents?.value
                val s = driver.snapshot()
                last = s
                res = when {
                    s != null && s.blockedReason != null -> false
                    s != null && cond(s) -> true
                    else -> { waitForChange(tick); null }
                }
            }
            res
        }
        return if (ok == true) last else null
    }

    private suspend fun waitForChange(tick: Long?) {
        val events = driver.uiEvents
        if (events == null || tick == null) {
            delay(POLL_MS)
            return
        }
        val changed = withTimeoutOrNull(POLL_MS) { events.first { it != tick } } != null
        if (changed) delay(DEBOUNCE_MS)
    }

    /** After an action: wait (bounded) for the UI to start changing, then a short debounce. */
    private suspend fun settle() {
        val events = driver.uiEvents
        if (events == null) { delay(DEBOUNCE_MS); return }
        val tick = events.value
        if (withTimeoutOrNull(SETTLE_MS) { events.first { it != tick } } != null) delay(DEBOUNCE_MS)
    }

    companion object {
        const val POLL_MS = 150L
        const val DEBOUNCE_MS = 60L
        const val SETTLE_MS = 250L
        const val POSTCONDITION_TIMEOUT_MS = 3_000L
        private val ID_RE = Regex("^[A-Za-z0-9_]{1,60}$")

        fun refOf(n: ScreenNode) = NodeRef(n.index, n.viewId, n.className)

        fun expectSatisfied(e: Expect, nodes: List<ScreenNode>): Boolean {
            if (e.isEmpty) return true
            val ids = nodes.mapNotNullTo(HashSet()) { it.idEntry?.lowercase() }
            val labels = HashSet<String>()
            nodes.forEach { n ->
                n.text?.trim()?.lowercase()?.let(labels::add)
                n.description?.trim()?.lowercase()?.let(labels::add)
            }
            val checks = e.ids.map { it.lowercase() in ids } + e.texts.map { it.trim().lowercase() in labels }
            return if (e.match == "any") checks.any { it } else checks.all { it }
        }

        /** Visible view-id entry names (no screen text), capped. */
        fun seenIds(s: NodeSnapshot): List<String> =
            s.nodes.mapNotNull { it.idEntry?.takeIf { id -> ID_RE.matches(id) } }.distinct().take(20)
    }
}
