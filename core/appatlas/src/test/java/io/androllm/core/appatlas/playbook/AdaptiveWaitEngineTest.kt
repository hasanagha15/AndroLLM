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
        assertThat(policy.observed.single()).isEqualTo(Triple("$PKG/open#0", false, false))
        assertThat(policy.ended).isTrue()
    }

    @Test
    fun `the doubled retry catches the slow screen and is reported`() = runTest {
        val driver = FakeDriver(slowScreen(1_500))
        val policy = Policy(WaitPlan(timeoutMs = 1_000, retryTimeoutMs = 2_000))
        val r = PlaybookEngine(driver, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(driver.taps).hasSize(1)
        assertThat(policy.observed.single()).isEqualTo(Triple("$PKG/open#0", true, true))
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
        val r = PlaybookEngine(driver, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
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
        val r = PlaybookEngine(driver, timing = policy).run(tapPlaybook, SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(currentTime).isLessThan(10_000L)
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
        assertThat(policy.observed.map { it.first }).containsExactly("$PKG/open#0", "$PKG/open#1").inOrder()
    }
}
