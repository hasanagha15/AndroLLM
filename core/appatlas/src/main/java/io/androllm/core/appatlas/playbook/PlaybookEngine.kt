package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.NodeRef
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.controller.RiskApproval
import io.androllm.core.accessibility.controller.RiskyElementGate
import io.androllm.core.accessibility.controller.ScreenNode
import io.androllm.core.accessibility.controller.UiActionResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /** The screen changed while the owner looked at a confirmation card: nothing was tapped. */
    SCREEN_CHANGED("screen_changed", false),
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
 * present before acting and a unique best node. The engine never scrolls on
 * its own in either mode (scrolling is an explicit `scroll` step).
 * Gate N-B2: a tap always needs an id / desc / text match (positional
 * locators only disambiguate); an element with no text, desc or id still
 * gets a tap-only card. After any card the target is re-resolved and must be
 * the same element before the tap.
 */
class PlaybookEngine(
    private val driver: PlaybookDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Branch T: how long / how carefully to wait for each transition. */
    private val timing: StepTimingPolicy = FixedStepTiming
) {

    suspend fun run(playbook: Playbook, slots: SlotValues, strict: Boolean): PlaybookRunResult =
        try { runSteps(playbook, slots, strict) } finally { runCatching { timing.end() } }

    private suspend fun runSteps(playbook: Playbook, slots: SlotValues, strict: Boolean): PlaybookRunResult {
        val start = clock()
        val stats = mutableListOf<String>()
        fun fail(f: StepFailure, done: Int) = PlaybookRunResult(
            false, done, hasPostcondition = playbook.postcondition != null, failure = f,
            locatorStats = stats, elapsedMs = clock() - start
        )

        val first = driver.snapshot() ?: return fail(StepFailure(0, playbook.steps.first().op, FailureKind.SERVICE_OFF), 0)
        if (first.blockedReason != null) return fail(StepFailure(0, playbook.steps.first().op, FailureKind.BLOCKED, seenPackage = first.packageName), 0)
        timing.begin(playbook, first)
        var launched = false
        if (first.packageName != playbook.packageName && playbook.steps.first().op != "open_deeplink") {
            driver.launch(playbook.packageName)?.let {
                return fail(StepFailure(0, playbook.steps.first().op, FailureKind.ACTION_FAILED, seenPackage = first.packageName), 0)
            }
            launched = true
        }
        // Branch T: transition time is measured from the end of the previous action.
        var actionEnd = clock()
        var prevOp: String? = null

        for ((i, step) in playbook.steps.withIndex()) {
            val minScore = step.minScore ?: Locator.DEFAULT_MIN_SCORE
            val needsApp = step.op != "open_deeplink" && step.op != "wait"
            val needsTarget = step.op == "tap" || (step.op == "type" && step.locators.isNotEmpty())

            // 1. Expected screen (+ the right app, + in strict mode the target itself).
            var resolution: Resolution? = null
            if (needsApp || step.expect != null) {
                // Branch T: the target is part of "expected screen" in both modes, so the
                // adaptive timeout + stability window cover the whole transition.
                val transition = Transition(playbook.packageName, playbook.id, i, kindFor(i, prevOp, launched, step))
                val plan = timing.plan(transition, step)
                val snap = awaitWithRetry(
                    transition, plan, actionEnd,
                    // Review R3: only "the expected screen did not come" counts as a slow screen.
                    slowScreen = { s -> s.packageName == playbook.packageName && step.expect != null && !expectSatisfied(step.expect, s.nodes) }
                ) { s ->
                    (!needsApp || s.packageName == playbook.packageName) &&
                        (step.expect == null || expectSatisfied(step.expect, s.nodes)) &&
                        (!needsTarget || resolve(step, s.nodes, minScore, strict) != null)
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
                        val snap = awaitScreen(timing.plan(Transition(playbook.packageName, playbook.id, i, TransitionKind.IN_APP), step)) { s ->
                            resolve(step, s.nodes, minScore, strict) != null
                        }
                        resolution = snap?.let { resolve(step, it.nodes, minScore, strict) }
                        if (resolution == null) {
                            val last = driver.snapshot() ?: return fail(StepFailure(i, step.op, FailureKind.SERVICE_OFF), i)
                            return fail(mismatch(i, step, FailureKind.TARGET_NOT_FOUND, last, strict), i)
                        }
                    }
                    resolution?.let { stats += "$i:${it.matched.joinToString("+")}" }
                    if (step.op == "tap") {
                        when (val t = tapWithGate(playbook, i, step, resolution!!, minScore, strict)) {
                            TapOutcome.Declined -> return fail(StepFailure(i, step.op, FailureKind.DECLINED), i)
                            TapOutcome.Changed -> return fail(StepFailure(i, step.op, FailureKind.SCREEN_CHANGED), i)
                            is TapOutcome.Done -> t.result
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
            actionEnd = clock()
            prevOp = step.op
            settle()
        }

        // 3. Cheap verifier.
        val post = playbook.postcondition
        val verified = if (post == null) false else {
            val postStep = PlaybookStep(op = "wait", expect = post, timeoutMs = POSTCONDITION_TIMEOUT_MS)
            val transition = Transition(playbook.packageName, playbook.id, playbook.steps.size, kindFor(playbook.steps.size, prevOp, false, postStep))
            val plan = timing.plan(transition, postStep)
            awaitWithRetry(
                transition, plan, actionEnd,
                slowScreen = { s -> s.packageName == playbook.packageName && !expectSatisfied(post, s.nodes) }
            ) { s -> s.packageName == playbook.packageName && expectSatisfied(post, s.nodes) } != null
        }
        return PlaybookRunResult(
            success = true, stepsDone = playbook.steps.size, verified = verified, hasPostcondition = post != null,
            locatorStats = stats, elapsedMs = clock() - start
        )
    }

    private sealed interface TapOutcome {
        data class Done(val result: UiActionResult) : TapOutcome
        data object Declined : TapOutcome
        data object Changed : TapOutcome
    }

    /** Taps with the confirmation gate; every card is followed by a re-check of the target. */
    private suspend fun tapWithGate(
        p: Playbook, i: Int, step: PlaybookStep, res: Resolution, minScore: Double, strict: Boolean
    ): TapOutcome {
        var node = res.node
        val label = RiskyElementGate.displayLabel(RiskyElementGate.Facts(node.text, node.description, node.viewId))
        // Gate N-B2 (defence in depth): nothing M's gate could classify → ask.
        val unlabeled = node.text.isNullOrBlank() && node.description.isNullOrBlank() && node.idEntry.isNullOrBlank()
        var approval: RiskApproval? = null
        if (step.risky || unlabeled) {
            // Declared / computed risky step: ask on a tap-only card, naming the playbook, step and element.
            if (!driver.confirmTap(card(p, i, label.ifEmpty { "an unlabeled element" }, node))) return TapOutcome.Declined
            node = reverify(p, step, node, minScore, strict) ?: return TapOutcome.Changed
            approval = RiskApproval(label, node.idEntry)
        }
        var r = driver.tap(refOf(node), approval)
        r.risky?.let { refusal ->
            // Branch M gate: the resolved element (or its clickable ancestor) is risky.
            if (!driver.confirmTap(card(p, i, refusal.element, node))) return TapOutcome.Declined
            node = reverify(p, step, node, minScore, strict) ?: return TapOutcome.Changed
            approval = RiskApproval(refusal.element, refusal.id)
            r = driver.tap(refOf(node), approval)
        }
        if (!r.success && r.retryable && r.risky == null) {
            settle()
            r = driver.tap(refOf(node), approval)
        }
        return TapOutcome.Done(r)
    }

    /**
     * After a card (the owner may take minutes): the app must still be in
     * front and the step must resolve to the SAME element (id, class, text,
     * desc, position). Null = changed → nothing is tapped.
     */
    private suspend fun reverify(p: Playbook, step: PlaybookStep, before: ScreenNode, minScore: Double, strict: Boolean): ScreenNode? {
        val snap = driver.snapshot() ?: return null
        if (snap.blockedReason != null || snap.packageName != p.packageName) return null
        val now = resolve(step, snap.nodes, minScore, strict)?.node ?: return null
        return now.takeIf { refOf(before).matches(it.viewId, it.className, it.text, it.description, it.centerX, it.centerY) }
    }

    /** Tap-only card text: the playbook, its action, the step and the element. */
    private fun card(p: Playbook, i: Int, label: String, node: ScreenNode): String =
        "playbook ${p.id} (${p.description.take(80)}), step ${i + 1}/${p.steps.size}: tap \"${label.take(160)}\"" +
            (node.idEntry?.let { " (#$it)" } ?: "")

    private fun resolve(step: PlaybookStep, nodes: List<ScreenNode>, minScore: Double, strict: Boolean): Resolution? =
        LocatorResolver.resolve(
            step.locators, nodes, minScore, strict,
            preferEditable = step.op == "type",
            requireSemantic = strict || step.op == "tap"
        )

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

    /** Branch T: which default applies when nothing is learned for this transition. */
    private fun kindFor(i: Int, prevOp: String?, launched: Boolean, step: PlaybookStep): TransitionKind = when {
        i == 0 && launched -> TransitionKind.LAUNCH
        prevOp == "open_deeplink" -> TransitionKind.DEEPLINK
        step.expect?.ids?.any { DIALOG_RE.containsMatchIn(it) } == true -> TransitionKind.DIALOG
        else -> TransitionKind.IN_APP
    }

    /**
     * Branch T: waits per [plan]; when the first wait times out (and nothing
     * protected is on screen) waits once more with [WaitPlan.retryTimeoutMs].
     * Reports the observed time to the policy: every success, but a failure
     * only when [slowScreen] holds on the last tree (review R3: a wrong app,
     * a protected screen or a broken locator says nothing about speed).
     */
    private suspend fun awaitWithRetry(
        t: Transition,
        plan: WaitPlan,
        since: Long,
        slowScreen: (NodeSnapshot) -> Boolean,
        cond: (NodeSnapshot) -> Boolean
    ): NodeSnapshot? {
        var snap = awaitScreen(plan, cond)
        var retried = false
        if (snap == null && plan.retryTimeoutMs > 0) {
            val peek = driver.snapshot()
            if (peek != null && peek.blockedReason == null) {
                retried = true
                snap = awaitScreen(plan.copy(timeoutMs = plan.retryTimeoutMs), cond)
            }
        }
        val ok = snap != null && snap.blockedReason == null
        val record = ok || driver.snapshot()?.let { it.blockedReason == null && slowScreen(it) } == true
        if (record) {
            // Review R6: a cancelled run (W deadline) must stop here, not continue to the tap.
            try {
                timing.observe(t, (clock() - since).coerceAtLeast(0L), ok, retried, snap)
            } catch (e: CancellationException) {
                throw e
            } catch (t2: Throwable) {
                // Timing is best effort.
            }
        }
        currentCoroutineContext().ensureActive()
        return snap
    }

    /**
     * Polls the tree until [cond] holds or the plan's timeout passes. Wakes on
     * UI events (debounced) and at least every [POLL_MS].
     *
     * Stability window (branch T): once [cond] holds, the tree must also stay
     * quiet (no UI event) for [WaitPlan.stableMs]. Animated screens never go
     * quiet, so the phase is capped by wall time measured with [clock] from
     * the first match (review B2): [WaitPlan.maxStableWaitMs], and never
     * later than the remaining timeout minus a debounce. At the cap the
     * condition is re-checked on a FRESH tree before it is accepted.
     * On a timeout the last held match is never returned as is: a fresh tree
     * must still pass [cond] (review B1). Null = timed out.
     */
    private suspend fun awaitScreen(plan: WaitPlan, cond: (NodeSnapshot) -> Boolean): NodeSnapshot? {
        var last: NodeSnapshot? = null
        // A match seen while the stability wait was running (only a hint: re-checked on timeout).
        var held: NodeSnapshot? = null
        val waitStart = clock()
        var stableStart = -1L
        val ok = withTimeoutOrNull(plan.timeoutMs) {
            var res: Boolean? = null
            while (res == null) {
                val events = driver.uiEvents
                val tick = events?.value
                val s = driver.snapshot()
                last = s
                res = when {
                    s != null && s.blockedReason != null -> false
                    s != null && cond(s) -> {
                        if (plan.stableMs <= 0 || events == null || tick == null) {
                            true
                        } else {
                            val now = clock()
                            if (stableStart < 0) stableStart = now
                            val capAt = minOf(
                                stableStart + plan.maxStableWaitMs,
                                waitStart + plan.timeoutMs - plan.debounceMs - STABLE_CAP_MARGIN_MS
                            )
                            if (now >= capAt) {
                                true // s is fresh: taken in this iteration, after the cap
                            } else {
                                held = s
                                val window = minOf(plan.stableMs, capAt - now)
                                val quiet = withTimeoutOrNull(window) { events.first { it != tick } } == null
                                when {
                                    quiet && window >= plan.stableMs -> true
                                    quiet -> null // window cut by the cap: re-read the tree, then accept
                                    else -> { delay(plan.debounceMs); null }
                                }
                            }
                        }
                    }
                    else -> { held = null; stableStart = -1L; waitForChange(tick, plan.debounceMs); null }
                }
            }
            res
        }
        return when (ok) {
            true -> last
            null -> if (held == null) null else driver.snapshot()?.takeIf { it.blockedReason == null && cond(it) }
            else -> null
        }
    }

    private suspend fun waitForChange(tick: Long?, debounceMs: Long) {
        val events = driver.uiEvents
        if (events == null || tick == null) {
            delay(POLL_MS)
            return
        }
        val changed = withTimeoutOrNull(POLL_MS) { events.first { it != tick } } != null
        if (changed) delay(debounceMs)
    }

    /** After an action: wait (bounded) for the UI to start changing, then a short debounce. */
    private suspend fun settle() {
        val p = timing.settle()
        val events = driver.uiEvents
        if (events == null) { delay(p.debounceMs); return }
        val tick = events.value
        if (withTimeoutOrNull(p.settleMs) { events.first { it != tick } } != null) delay(p.debounceMs)
    }

    companion object {
        /** The stability phase ends at least this long before the step timeout. */
        const val STABLE_CAP_MARGIN_MS = 50L
        private val DIALOG_RE = Regex("dialog|alert|sheet|popup|picker", RegexOption.IGNORE_CASE)
        const val POLL_MS = 150L
        const val DEBOUNCE_MS = 60L
        const val SETTLE_MS = 250L
        const val POSTCONDITION_TIMEOUT_MS = 3_000L
        private val ID_RE = Regex("^[A-Za-z0-9_]{1,60}$")

        fun refOf(n: ScreenNode) = NodeRef.of(n)

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
