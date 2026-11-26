package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Branch T: the engine's adaptive wait (timeout + one doubled retry, stability window). */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveWaitEngineTest {

    private class Policy(val plan: WaitPlan) : StepTimingPolicy {
        val observed = mutableListOf<Triple<String, Boolean, Boolean>>()
        var ended = false
        override suspend fun plan(t: Transition, step: PlaybookStep) = plan
        override suspend fun settle() = SettlePlan()
        override suspend fun observe(t: Transition, elapsedMs: Long, ok: Boolean, retried: Boolean, snapshot: NodeSnapshot?) {
            observed += Triple(t.key, ok, retried)
        }
        override suspend fun end() { ended = true }
    }

    private val tapPlaybook = Playbook(
        id = "$PKG/open", packageName = PKG, name = "open", description = "Open library", versionMin = 1, versionMax = 9,
        steps = listOf(PlaybookStep(op = "tap", locators = listOf(Locator("id", "library_tab"))))
    )

    /** The target appears only after [appearAtMs] of virtual time (a slow, loaded phone). */
    private fun TestScope.slowScreen(appearAtMs: Long): () -> NodeSnapshot = {
        val nodes = mutableListOf(node(0, "toolbar"))
        if (currentTime >= appearAtMs) nodes += node(1, "library_tab", text = "Library")
        NodeSnapshot(PKG, nodes)
    }

    @Test
    fun `without a retry a slow screen fails as a mismatch`() = runTest {
        val driver = FakeDriver(slowScreen(1_500))
        val policy = Policy(WaitPlan(timeoutMs = 1_000))
        val r = PlaybookEngine(driver, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isFalse()
        assertThat(r.failure!!.kind).isEqualTo(FailureKind.TARGET_NOT_FOUND)
        // Review R3: a missing target on the expected screen is not a timing sample.
        assertThat(policy.observed).isEmpty()
        assertThat(policy.ended).isTrue()
    }

    @Test
    fun `R3 - only an expected screen that did not come is recorded as a slow failure`() = runTest {
        val p = tapPlaybook.copy(
            steps = listOf(PlaybookStep(op = "tap", locators = listOf(Locator("id", "library_tab")), expect = Expect(ids = listOf("library_header"))))
        )
        // The expected header never appears: EXPECT_TIMEOUT, recorded with ok = false.
        val slow = Policy(WaitPlan(timeoutMs = 500))
        PlaybookEngine(FakeDriver(slowScreen(0)), timing = slow).run(p, SlotValues.EMPTY, strict = false)
        assertThat(slow.observed.single()).isEqualTo(Triple("$PKG/open#0:in_app", false, false))
        // Another app stays in front: WRONG_APP, not recorded.
        val wrong = Policy(WaitPlan(timeoutMs = 500))
        val other = FakeDriver { NodeSnapshot("com.example.other", listOf(node(0, "x", pkg = "com.example.other"))) }
        val r = PlaybookEngine(other, timing = wrong).run(p, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isFalse()
        assertThat(wrong.observed).isEmpty()
    }

    @Test
    fun `the doubled retry catches the slow screen and is reported`() = runTest {
        val driver = FakeDriver(slowScreen(1_500))
        val policy = Policy(WaitPlan(timeoutMs = 1_000, retryTimeoutMs = 2_000))
        val r = PlaybookEngine(driver, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(driver.taps).hasSize(1)
        assertThat(policy.observed.single()).isEqualTo(Triple("$PKG/open#0:in_app", true, true))
    }

    @Test
    fun `fixed timing keeps the branch N behaviour`() = runTest {
        val driver = FakeDriver(slowScreen(1_500))
        val r = PlaybookEngine(driver).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        // 4 s default step timeout covers 1.5 s.
        assertThat(r.success).isTrue()
    }

    @Test
    fun `stability window waits for a quiet tree before acting`() = runTest {
        val driver = FakeDriver(slowScreen(0))
        // UI events for the first 500 ms (an animation), then quiet.
        val anim = launch { repeat(10) { delay(50); driver.events.value++ } }
        val policy = Policy(WaitPlan(timeoutMs = 5_000, stableMs = 200, maxStableWaitMs = 2_000, debounceMs = 60))
        val r = PlaybookEngine(driver, clock = { currentTime }, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        anim.join()
        assertThat(r.success).isTrue()
        assertThat(currentTime).isAtLeast(500L)
        assertThat(currentTime).isLessThan(2_000L)
    }

    @Test
    fun `a screen that never goes quiet is accepted after the stability cap`() = runTest {
        val driver = FakeDriver(slowScreen(0))
        backgroundScope.launch { while (true) { delay(20); driver.events.value++ } }
        val policy = Policy(WaitPlan(timeoutMs = 10_000, stableMs = 200, maxStableWaitMs = 600, debounceMs = 60))
        val r = PlaybookEngine(driver, clock = { currentTime }, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(currentTime).isLessThan(1_500L)
    }

    @Test
    fun `B2 - stability is capped by wall time, an event every 150 ms still succeeds before the timeout`() = runTest {
        val driver = FakeDriver(slowScreen(0))
        backgroundScope.launch { while (true) { delay(150); driver.events.value++ } }
        // No retry: a success proves the first wait (1500 ms) did not time out.
        val policy = Policy(WaitPlan(timeoutMs = 1_500, stableMs = 200, maxStableWaitMs = 600, debounceMs = 60))
        var tappedAt = -1L
        driver.onTap = { tappedAt = currentTime }
        val r = PlaybookEngine(driver, clock = { currentTime }, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(tappedAt).isAtLeast(600L)
        assertThat(tappedAt).isLessThan(900L)
        assertThat(policy.observed.single()).isEqualTo(Triple("$PKG/open#0:in_app", true, false))
    }

    @Test
    fun `B1 - a held match is re-checked on a fresh tree at the timeout, no tap on a changed screen`() = runTest {
        // The target is visible until 990 ms; events keep coming; the clock never reaches the stability cap.
        val driver = FakeDriver(slowScreen(0))
        driver.screen = {
            val nodes = mutableListOf(node(0, "toolbar"))
            if (currentTime < 990) nodes += node(1, "library_tab", text = "Library")
            NodeSnapshot(PKG, nodes)
        }
        backgroundScope.launch { while (true) { delay(50); driver.events.value++ } }
        val policy = Policy(WaitPlan(timeoutMs = 1_000, stableMs = 200, maxStableWaitMs = 60_000, debounceMs = 60))
        val r = PlaybookEngine(driver, clock = { 0L }, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isFalse()
        assertThat(driver.taps).isEmpty()
    }

    @Test
    fun `postcondition gets its own adaptive wait`() = runTest {
        var tapped = false
        val driver = FakeDriver {
            val nodes = mutableListOf(node(0, "library_tab", text = "Library"))
            if (tapped) nodes += node(1, "library_list")
            NodeSnapshot(PKG, nodes)
        }
        driver.onTap = { tapped = true }
        val p = tapPlaybook.copy(postcondition = Expect(ids = listOf("library_list")))
        val policy = Policy(WaitPlan(timeoutMs = 1_000, retryTimeoutMs = 2_000))
        val r = PlaybookEngine(driver, timing = policy).run(p, SlotValues.EMPTY, strict = false)
        assertThat(r.verified).isTrue()
        assertThat(policy.observed.map { it.first }).containsExactly("$PKG/open#0:in_app", "$PKG/open#1:in_app").inOrder()
    }
}
