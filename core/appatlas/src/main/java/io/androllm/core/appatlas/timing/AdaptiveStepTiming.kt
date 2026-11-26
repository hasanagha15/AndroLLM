package io.androllm.core.appatlas.timing

import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.timing.DeviceLoadMonitor
import io.androllm.core.appatlas.playbook.Playbook
import io.androllm.core.appatlas.playbook.PlaybookEngine
import io.androllm.core.appatlas.playbook.PlaybookStep
import io.androllm.core.appatlas.playbook.SettlePlan
import io.androllm.core.appatlas.playbook.StepTimingPolicy
import io.androllm.core.appatlas.playbook.Transition
import io.androllm.core.appatlas.playbook.TransitionKind
import io.androllm.core.appatlas.playbook.WaitPlan
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import timber.log.Timber

/**
 * Branch T: playbook waits calibrated to the phone's current load.
 *
 *   k'      = 1 + (k − 1) × sensitivity(kind)      (launches feel the load fully, in-app ≈ 20 %)
 *   timeout = clamp(p95 × k' × MARGIN, min(kind), max(kind))   (+ one retry with 2 × timeout)
 *   debounce = clamp(debounce(uiType) × k, 60, 300)
 *   stable   = clamp(stable(uiType) × k, 0, 600), at most 4 × stable of waiting for quiet
 *   settle   = clamp(250 × k, 250, 800)
 *
 * p95 = learned (load-normalized) p95 of this transition, or the default of
 * its kind × UI type; k = [DeviceLoadMonitor.loadFactor]. A step's explicit
 * timeoutMs (≠ the default) is kept as a floor. One run at a time (the
 * runner's mutex), so the per-run state below is not shared.
 */
@Singleton
class AdaptiveStepTiming @Inject constructor(
    private val load: DeviceLoadMonitor,
    private val model: TransitionTimingModel
) : StepTimingPolicy {

    private var uiType: AppUiType = AppUiType.UNKNOWN
    private var packageName: String? = null
    /** k each transition was planned under (to normalize the observation). */
    private val plannedK = HashMap<String, Double>()

    override suspend fun begin(playbook: Playbook, first: NodeSnapshot) {
        packageName = playbook.packageName
        plannedK.clear()
        uiType = if (first.packageName == playbook.packageName) AppUiType.detect(first) else AppUiType.UNKNOWN
        if (uiType == AppUiType.UNKNOWN) uiType = model.uiTypeHint(playbook.packageName)
    }

    override suspend fun plan(t: Transition, step: PlaybookStep): WaitPlan {
        val k = effectiveK(t.kind, load.loadFactorAsync())
        plannedK[t.key] = k
        val est = model.estimate(t, uiType)
        return planFor(t.kind, uiType, est, k, step.timeoutMs)
    }

    override suspend fun settle(): SettlePlan {
        // Review R7: after an action the next change is usually in-app → scale with k', not k.
        val k = effectiveK(TransitionKind.IN_APP, load.loadFactorAsync())
        return SettlePlan(
            settleMs = (PlaybookEngine.SETTLE_MS * k).roundToLong().coerceIn(PlaybookEngine.SETTLE_MS, SETTLE_MAX_MS),
            debounceMs = debounceFor(uiType, k)
        )
    }

    override suspend fun observe(t: Transition, elapsedMs: Long, ok: Boolean, retried: Boolean, snapshot: NodeSnapshot?) {
        if (ok && snapshot?.packageName == t.packageName) {
            AppUiType.detect(snapshot).takeIf { it != AppUiType.UNKNOWN }?.let { uiType = it }
        }
        val k = plannedK[t.key] ?: effectiveK(t.kind, load.loadFactorAsync())
        model.record(t, uiType, elapsedMs / k, ok)
        if (retried) Timber.i("Timing: %s needed the retry (ok=%s, k=%.2f)", t.key, ok, k)
    }

    override suspend fun end() {
        model.flush()
    }

    override suspend fun forget(packageName: String) {
        model.forget(listOf(packageName))
    }

    companion object {
        const val MARGIN = 1.5
        const val RETRY_FACTOR = 2
        const val SETTLE_MAX_MS = 800L
        const val DEBOUNCE_MIN_MS = 60L
        const val DEBOUNCE_MAX_MS = 300L
        const val STABLE_MAX_MS = 600L
        const val STABLE_WAIT_FACTOR = 4
        const val RETRY_MAX_MS = 30_000L

        /**
         * How strongly a kind of transition follows the load factor (branch T
         * measurements: generation slowed launches ×2–3 but in-app tab /
         * screen changes only ×1.0–1.15).
         */
        fun sensitivity(kind: TransitionKind): Double = when (kind) {
            TransitionKind.LAUNCH, TransitionKind.DEEPLINK -> 1.0
            TransitionKind.IN_APP, TransitionKind.DIALOG -> 0.2
        }

        fun effectiveK(kind: TransitionKind, k: Double): Double = 1.0 + (k - 1.0) * sensitivity(kind)

        fun minTimeout(kind: TransitionKind): Long = when (kind) {
            TransitionKind.LAUNCH -> 3_000L
            TransitionKind.DEEPLINK -> 2_500L
            TransitionKind.IN_APP, TransitionKind.DIALOG -> 1_500L
        }

        fun maxTimeout(kind: TransitionKind): Long = when (kind) {
            TransitionKind.LAUNCH, TransitionKind.DEEPLINK -> 20_000L
            TransitionKind.IN_APP, TransitionKind.DIALOG -> 15_000L
        }

        fun debounceFor(type: AppUiType, k: Double): Long =
            (type.debounceMs * k).roundToLong().coerceIn(DEBOUNCE_MIN_MS, DEBOUNCE_MAX_MS)

        /** Pure: the wait plan for one transition (unit tested). */
        fun planFor(kind: TransitionKind, type: AppUiType, est: TimingEstimate, k: Double, declaredTimeoutMs: Long): WaitPlan {
            var timeout = (est.p95Ms * k * MARGIN).roundToLong().coerceIn(minTimeout(kind), maxTimeout(kind))
            if (declaredTimeoutMs != PlaybookStep.DEFAULT_TIMEOUT_MS) timeout = maxOf(timeout, declaredTimeoutMs)
            val stable = (type.stableMs * k).roundToLong().coerceIn(0L, STABLE_MAX_MS)
            return WaitPlan(
                timeoutMs = timeout,
                retryTimeoutMs = (timeout * RETRY_FACTOR).coerceAtMost(RETRY_MAX_MS),
                debounceMs = debounceFor(type, k),
                stableMs = stable,
                maxStableWaitMs = stable * STABLE_WAIT_FACTOR
            )
        }
    }
}
