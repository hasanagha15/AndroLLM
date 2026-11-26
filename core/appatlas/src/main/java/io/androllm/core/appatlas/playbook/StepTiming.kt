package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.NodeSnapshot

/** Branch T: what kind of screen change the engine is waiting for. */
enum class TransitionKind(val wire: String) {
    /** The app was brought to the front by its launcher intent (cold / warm / hot start). */
    LAUNCH("launch"),
    /** After open_deeplink: activity start inside the app, often with network content. */
    DEEPLINK("deeplink"),
    /** After a tap / back / scroll / type inside the app. */
    IN_APP("in_app"),
    /** A dialog / bottom sheet / popup over the current screen. */
    DIALOG("dialog");

    companion object {
        fun of(wire: String): TransitionKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One awaited transition: playbook id + step index identify it (no screen
 * text); [kind] picks the default when nothing is learned yet.
 */
data class Transition(
    val packageName: String,
    val playbookId: String,
    val stepIndex: Int,
    val kind: TransitionKind
) {
    /**
     * Stable, content-free key: "<playbook id>#<step>:<kind>". The kind is
     * part of it (review R1): step 0 is a LAUNCH when the app had to be
     * started and IN_APP when it was already open — different populations.
     */
    val key: String get() = "$playbookId#$stepIndex:${kind.wire}"
}

/** How long and how carefully to wait for one transition. */
data class WaitPlan(
    /** First wait for the expected screen. */
    val timeoutMs: Long,
    /** Extra wait when the first one timed out (0 = fail at once). Branch T: 2 × [timeoutMs]. */
    val retryTimeoutMs: Long = 0L,
    /** After a UI event: let the burst finish before re-reading the tree. */
    val debounceMs: Long = PlaybookEngine.DEBOUNCE_MS,
    /** "Screen stable": the condition must hold with no UI event for this long (0 = act at once). */
    val stableMs: Long = 0L,
    /** Upper bound on the stability wait once the condition holds (animated screens never go quiet). */
    val maxStableWaitMs: Long = 0L
)

/** After an action: how long to wait for the UI to start reacting, then the debounce. */
data class SettlePlan(
    val settleMs: Long = PlaybookEngine.SETTLE_MS,
    val debounceMs: Long = PlaybookEngine.DEBOUNCE_MS
)

/**
 * Branch T: timing policy of the replay engine. [FixedStepTiming] = the
 * branch N behaviour (step timeout, 60 ms debounce, no retry); production
 * uses the adaptive policy (learned p95 × load factor k).
 */
interface StepTimingPolicy {
    /** Called once per run with the first snapshot (app-type hint, load sample). */
    suspend fun begin(playbook: Playbook, first: NodeSnapshot) {}
    suspend fun plan(t: Transition, step: PlaybookStep): WaitPlan
    suspend fun settle(): SettlePlan
    /**
     * [elapsedMs]: from the end of the previous action to "expected screen
     * stable" (success) or to the final timeout (failure). [retried] = the
     * first wait timed out.
     */
    suspend fun observe(t: Transition, elapsedMs: Long, ok: Boolean, retried: Boolean, snapshot: NodeSnapshot?) {}
    /** End of the run: persist what was learned. */
    suspend fun end() {}
    /** Review R4: [packageName] is uninstalled or protected — drop its timings. */
    suspend fun forget(packageName: String) {}
}

/** Branch N behaviour, unchanged. */
object FixedStepTiming : StepTimingPolicy {
    override suspend fun plan(t: Transition, step: PlaybookStep) = WaitPlan(timeoutMs = step.timeoutMs)
    override suspend fun settle() = SettlePlan()
}
