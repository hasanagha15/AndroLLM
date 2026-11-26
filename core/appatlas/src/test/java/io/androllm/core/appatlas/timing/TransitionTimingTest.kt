package io.androllm.core.appatlas.timing

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.timing.DeviceLoadMonitor
import io.androllm.core.accessibility.timing.LoadSample
import io.androllm.core.appatlas.playbook.PlaybookFixtures
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.appatlas.playbook.PlaybookStep
import io.androllm.core.appatlas.playbook.Transition
import io.androllm.core.appatlas.playbook.TransitionKind
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TransitionTimingTest {

    private val t = Transition(PKG, "$PKG/search", 1, TransitionKind.IN_APP)

    // ── TimingStats (EWMA) ──────────────────────────────────────────────────

    @Test
    fun `ewma converges to the typical time and p95 stays above p50`() {
        var s = TimingStats()
        repeat(50) { i -> s = s.update(if (i % 5 == 0) 600.0 else 400.0, i.toLong()) }
        assertThat(s.p50Ms).isWithin(80.0).of(440.0)
        assertThat(s.p95Ms).isAtLeast(s.p50Ms * TimingStats.P95_MIN_RATIO)
        assertThat(s.p95Ms).isAtLeast(500.0)
        assertThat(s.n).isEqualTo(50)
    }

    @Test
    fun `first samples are averaged, not swamped by alpha`() {
        val s = TimingStats().update(1000.0, 0).update(500.0, 1)
        assertThat(s.p50Ms).isWithin(1e-6).of(750.0)
    }

    @Test
    fun `one huge outlier is clamped`() {
        var s = TimingStats()
        repeat(20) { s = s.update(300.0, 0) }
        val before = s.p95Ms
        s = s.update(60_000.0, 1)
        assertThat(s.p50Ms).isLessThan(300.0 + 0.2 * before * TimingStats.OUTLIER_X)
        assertThat(s.p50Ms).isLessThan(1_000.0)
    }

    @Test
    fun `a timeout widens the spread and counts a failure`() {
        var s = TimingStats()
        repeat(10) { s = s.update(300.0, 0) }
        val f = s.failed(3_000.0, 1)
        assertThat(f.fails).isEqualTo(1)
        assertThat(f.p95Ms).isGreaterThan(s.p95Ms)
        assertThat(f.p50Ms).isEqualTo(s.p50Ms)
    }

    // ── model: defaults, blending, persistence ──────────────────────────────

    @Test
    fun `unknown transition uses the default of its kind and ui type`() = runTest {
        val m = TransitionTimingModel(MemoryTimingStore())
        val inApp = m.estimate(t, AppUiType.VIEWS)
        val launch = m.estimate(t.copy(kind = TransitionKind.LAUNCH), AppUiType.VIEWS)
        val web = m.estimate(t, AppUiType.WEB)
        assertThat(inApp.learnedRuns).isEqualTo(0)
        assertThat(launch.p95Ms).isGreaterThan(inApp.p95Ms)
        assertThat(web.p95Ms).isGreaterThan(inApp.p95Ms)
        assertThat(inApp.p95Ms).isEqualTo(TimingDefaults.of(TransitionKind.IN_APP, AppUiType.VIEWS).p95Ms)
    }

    @Test
    fun `one fast run cannot shrink the timeout below the learned p95, blends towards default`() = runTest {
        val m = TransitionTimingModel(MemoryTimingStore())
        m.record(t, AppUiType.VIEWS, 100.0, ok = true)
        val e = m.estimate(t, AppUiType.VIEWS)
        val d = TimingDefaults.of(TransitionKind.IN_APP, AppUiType.VIEWS)
        assertThat(e.learnedRuns).isEqualTo(1)
        assertThat(e.p95Ms).isGreaterThan(100.0)
        assertThat(e.p95Ms).isLessThan(d.p95Ms)
        repeat(5) { m.record(t, AppUiType.VIEWS, 100.0, ok = true) }
        assertThat(m.estimate(t, AppUiType.VIEWS).p95Ms).isLessThan(200.0)
    }

    @Test
    fun `flush persists only content-free rows and a new model reads them back`() = runTest {
        val store = MemoryTimingStore()
        val m = TransitionTimingModel(store, clock = { 42L })
        repeat(4) { m.record(t, AppUiType.COMPOSE, 500.0, ok = true) }
        assertThat(store.rows).isEmpty()
        m.flush()
        val row = store.rows.values.single()
        assertThat(row.transitionKey).isEqualTo("$PKG/search#1")
        assertThat(row.kind).isEqualTo(TransitionKind.IN_APP)
        assertThat(row.uiType).isEqualTo(AppUiType.COMPOSE)
        assertThat(row.stats.updatedAt).isEqualTo(42L)

        val again = TransitionTimingModel(store)
        assertThat(again.estimate(t, AppUiType.VIEWS).p50Ms).isWithin(1e-6).of(500.0)
        assertThat(again.uiTypeHint(PKG)).isEqualTo(AppUiType.COMPOSE)
    }

    @Test
    fun `room row round trip`() {
        val rec = TimingRecord(PKG, "$PKG/search#2", TransitionKind.DEEPLINK, AppUiType.WEB, TimingStats(7, 812.5, 120.0, 1, 99L))
        assertThat(RoomTimingStore.decode(RoomTimingStore.encode(rec))).isEqualTo(rec)
        assertThat(RoomTimingStore.decode(RoomTimingStore.encode(rec).copy(kind = "bogus"))).isNull()
    }

    // ── ui type detection ───────────────────────────────────────────────────

    @Test
    fun `ui type from class names and ids only`() {
        val views = NodeSnapshot(PKG, (0 until 8).map { node(it, id = "v$it") })
        val compose = NodeSnapshot(PKG, (0 until 8).map { node(it, cls = "android.view.View") })
        val web = NodeSnapshot(PKG, (0 until 8).map { node(it, id = "v$it", cls = if (it == 3) "android.webkit.WebView" else "android.widget.TextView") })
        assertThat(AppUiType.detect(views)).isEqualTo(AppUiType.VIEWS)
        assertThat(AppUiType.detect(compose)).isEqualTo(AppUiType.COMPOSE)
        assertThat(AppUiType.detect(web)).isEqualTo(AppUiType.WEB)
        assertThat(AppUiType.detect(NodeSnapshot(PKG, listOf(node(0))))).isEqualTo(AppUiType.UNKNOWN)
    }

    // ── adaptive plan: clamp(p95 × k × margin), retry, debounce ─────────────

    private val def = PlaybookStep.DEFAULT_TIMEOUT_MS

    @Test
    fun `timeout is p95 times k times margin, clamped`() {
        val est = TimingEstimate(p50Ms = 800.0, p95Ms = 2_000.0, learnedRuns = 10)
        val idle = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est, 1.0, def)
        assertThat(idle.timeoutMs).isEqualTo(3_000)
        assertThat(idle.retryTimeoutMs).isEqualTo(6_000)
        val busy = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est, 2.0, def)
        assertThat(busy.timeoutMs).isEqualTo(6_000)
        // Floor and ceiling.
        val tiny = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est.copy(p95Ms = 100.0), 1.0, def)
        assertThat(tiny.timeoutMs).isEqualTo(AdaptiveStepTiming.minTimeout(TransitionKind.IN_APP))
        val huge = AdaptiveStepTiming.planFor(TransitionKind.LAUNCH, AppUiType.VIEWS, est.copy(p95Ms = 50_000.0), 3.0, def)
        assertThat(huge.timeoutMs).isEqualTo(AdaptiveStepTiming.maxTimeout(TransitionKind.LAUNCH))
        assertThat(huge.retryTimeoutMs).isEqualTo(AdaptiveStepTiming.RETRY_MAX_MS)
    }

    @Test
    fun `an explicit step timeout is kept as a floor`() {
        val est = TimingEstimate(300.0, 600.0, 10)
        val p = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est, 1.0, 9_000)
        assertThat(p.timeoutMs).isEqualTo(9_000)
    }

    @Test
    fun `debounce and stability window grow with k and with slower ui types`() {
        val est = TimingEstimate(300.0, 600.0, 10)
        val v = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est, 1.0, def)
        val w = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.WEB, est, 1.0, def)
        val v3 = AdaptiveStepTiming.planFor(TransitionKind.IN_APP, AppUiType.VIEWS, est, 3.0, def)
        assertThat(v.debounceMs).isEqualTo(60)
        assertThat(w.debounceMs).isGreaterThan(v.debounceMs)
        assertThat(w.stableMs).isGreaterThan(v.stableMs)
        assertThat(v3.debounceMs).isEqualTo(180)
        assertThat(v3.stableMs).isEqualTo(240)
        assertThat(v3.maxStableWaitMs).isEqualTo(960)
    }

    @Test
    fun `policy normalizes observations by the k they were planned under`() = runTest {
        val store = MemoryTimingStore()
        val model = TransitionTimingModel(store)
        val policy = AdaptiveStepTiming(DeviceLoadMonitor.fixed(LoadSample(powerSave = true, modelGenerating = true)), model)
        val p = PlaybookFixtures.searchPlaybook()
        policy.begin(p, NodeSnapshot(PKG, (0 until 8).map { node(it, id = "v$it") }))
        val step = p.steps[1]
        val plan = policy.plan(t, step)
        assertThat(plan.retryTimeoutMs).isEqualTo(plan.timeoutMs * 2)
        policy.observe(t, 1_600, ok = true, retried = false, snapshot = null)
        policy.end()
        // Raw k = 1 + saver + generating; an in-app transition feels 20 % of it.
        val raw = 1.0 + io.androllm.core.accessibility.timing.LoadFactor.SAVER + io.androllm.core.accessibility.timing.LoadFactor.MODEL_GENERATING
        val k = AdaptiveStepTiming.effectiveK(TransitionKind.IN_APP, raw)
        assertThat(k).isWithin(1e-9).of(1.0 + (raw - 1.0) * 0.2)
        assertThat(store.rows.values.single().stats.p50Ms).isWithin(1.0).of(1_600 / k)
        assertThat(store.rows.values.single().uiType).isEqualTo(AppUiType.VIEWS)
    }

    @Test
    fun `launches follow the load fully, in-app transitions only partly`() {
        assertThat(AdaptiveStepTiming.effectiveK(TransitionKind.LAUNCH, 2.0)).isEqualTo(2.0)
        assertThat(AdaptiveStepTiming.effectiveK(TransitionKind.DEEPLINK, 2.0)).isEqualTo(2.0)
        assertThat(AdaptiveStepTiming.effectiveK(TransitionKind.IN_APP, 2.0)).isWithin(1e-9).of(1.2)
        assertThat(AdaptiveStepTiming.effectiveK(TransitionKind.DIALOG, 1.0)).isEqualTo(1.0)
    }

    @Test
    fun `sync rendering is content-free and capped`() {
        val rows = (0 until 200).map {
            TimingRecord(PKG, "$PKG/search#$it", TransitionKind.IN_APP, AppUiType.VIEWS, TimingStats(3, 400.0, 50.0, 0, 1L))
        }
        val out = TimingSyncHandler.render(rows, 1.234, 2_000)
        assertThat(out.length).isAtMost(2_000)
        assertThat(out).startsWith("{\"k\":1.23,")
        assertThat(out).endsWith("\"more\":true}")
        assertThat(out).contains("\"p95\":525")
    }
}
