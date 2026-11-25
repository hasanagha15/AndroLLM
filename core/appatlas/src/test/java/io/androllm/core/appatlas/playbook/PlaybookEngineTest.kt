package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.tools.api.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybookEngineTest {

    private fun slots(p: Playbook, vararg kv: Pair<String, String>) = (PlaybookSlots.fill(p, kv.toMap()) as SlotFill.Ok).slots

    // ── risky-step confirmation ─────────────────────────────────────────────

    private val sendScreen = NodeSnapshot(PKG, listOf(node(0, "comment_edit", editable = true), node(1, "send_button", text = "Send")))

    @Test
    fun `declared risky step asks on a tap-only card naming the resolved element, then taps with that approval`() = runTest {
        val p = PlaybookValidator.withComputedRisk(PlaybookFixtures.sendPlaybook())
        val driver = FakeDriver { sendScreen }
        val r = PlaybookEngine(driver).run(p, slots(p, "text" to "hello"), strict = false)
        assertThat(r.success).isTrue()
        assertThat(driver.confirmations).hasSize(1)
        assertThat(driver.confirmations.single()).contains("Send")
        assertThat(driver.confirmations.single()).contains("#send_button")
        val (_, approval) = driver.taps.single()
        assertThat(approval!!.label).isEqualTo("Send")
        assertThat(approval.id).isEqualTo("send_button")
    }

    @Test
    fun `declined card stops before the tap`() = runTest {
        val p = PlaybookValidator.withComputedRisk(PlaybookFixtures.sendPlaybook())
        val driver = FakeDriver { sendScreen }.apply { approve = false }
        val r = PlaybookEngine(driver).run(p, slots(p, "text" to "hello"), strict = false)
        assertThat(r.success).isFalse()
        assertThat(r.failure!!.kind).isEqualTo(FailureKind.DECLINED)
        assertThat(driver.taps).isEmpty()
    }

    @Test
    fun `undeclared step whose resolved element is risky is gated by the controller decision`() = runTest {
        // Locator is a neutral id, but the element found is «Отправить» → branch M gate refuses → card → approved tap.
        val p = Playbook(
            id = "$PKG/go", packageName = PKG, name = "go", description = "Go", versionMin = 1, versionMax = 9,
            steps = listOf(PlaybookStep(op = "tap", locators = listOf(Locator("id", "action_main"))))
        )
        val screen = NodeSnapshot(PKG, listOf(node(0, "action_main", text = "Отправить")))
        val driver = FakeDriver { screen }
        val r = PlaybookEngine(driver).run(PlaybookValidator.withComputedRisk(p), SlotValues.EMPTY, strict = false)
        assertThat(r.success).isTrue()
        assertThat(driver.confirmations.single()).contains("Отправить")
        assertThat(driver.taps.single().second!!.label).isEqualTo("Отправить")
    }

    @Test
    fun `non-risky playbook never asks`() = runTest {
        val p = PlaybookFixtures.searchPlaybook(postcondition = null)
        val screen = NodeSnapshot(PKG, listOf(node(0, "toolbar"), node(1, "search_button"), node(2, "search_edit", editable = true), node(3, "go_button")))
        val driver = FakeDriver { screen }
        val r = PlaybookEngine(driver).run(p, slots(p, "query" to "cats"), strict = false)
        assertThat(r.success).isTrue()
        assertThat(driver.confirmations).isEmpty()
        assertThat(driver.taps.map { it.second }).containsExactly(null, null)
        assertThat(r.locatorStats.first()).isEqualTo("0:id")
    }

    // ── waits, postcondition, strict mode ───────────────────────────────────

    @Test
    fun `waits for the expected screen to appear and reports verified`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        var appeared = false
        var searched = false
        val driver = FakeDriver {
            val nodes = mutableListOf(node(1, "search_button"), node(2, "search_edit", editable = true), node(3, "go_button"))
            if (appeared) nodes += node(0, "toolbar")
            if (searched) nodes += node(4, "results_list")
            NodeSnapshot(PKG, nodes)
        }
        driver.onTap = { ref -> if (ref.viewId?.endsWith("go_button") == true) searched = true }
        // The toolbar shows up after a UI event.
        val engine = PlaybookEngine(driver)
        val job = launch { delay(500); appeared = true; driver.events.value++ }
        val r = engine.run(p, slots(p, "query" to "cats"), strict = false)
        job.join()
        assertThat(r.success).isTrue()
        assertThat(r.verified).isTrue()
    }

    @Test
    fun `positional-only taps never resolve, in any mode (gate N-B2)`() = runTest {
        val p = Playbook(
            id = "$PKG/go", packageName = PKG, name = "go", description = "Go", versionMin = 1, versionMax = 9,
            steps = listOf(PlaybookStep(op = "tap", timeoutMs = 500, locators = listOf(
                Locator("class_index", "Button", index = 0, weight = 0.5), Locator("bounds", bounds = listOf(0.0, 0.0, 1.0, 1.0))
            )))
        )
        val screen = NodeSnapshot(PKG, listOf(node(0, cls = "android.widget.Button")))
        val normal = FakeDriver { screen }
        assertThat(PlaybookEngine(normal).run(p, SlotValues.EMPTY, strict = false).success).isFalse()
        assertThat(normal.taps).isEmpty()
        val strict = PlaybookEngine(FakeDriver { screen }).run(p, SlotValues.EMPTY, strict = true)
        assertThat(strict.success).isFalse()
        assertThat(strict.failure!!.kind.mismatch).isTrue()
    }

    @Test
    fun `protected app on screen stops without reading it`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        val driver = FakeDriver { NodeSnapshot("com.whatsapp", emptyList(), blockedReason = "protected") }
        val r = PlaybookEngine(driver).run(p, slots(p, "query" to "x"), strict = false)
        assertThat(r.failure!!.kind).isEqualTo(FailureKind.BLOCKED)
        assertThat(driver.taps).isEmpty()
    }

    // ── mismatch → stale → relearn ──────────────────────────────────────────

    @Test
    fun `mismatch marks the playbook stale for that version, queues a relearn record and falls back`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = PlaybookFixtures.searchPlaybook()
        store.items[p.id] = StoredPlaybook(p)
        val driver = FakeDriver { NodeSnapshot(PKG, listOf(node(0, "home_feed"), node(1, "avatar"))) }
        val goals = mutableListOf<String>()
        val runner = PlaybookRunner(repo, driver, { 150L }, { "Example Video" }, fallback = { g -> goals += g; FallbackOutcome(true, "done") })

        val first = runner.run(p.id, mapOf("query" to "cats"), allowFallback = true)
        assertThat(first).isInstanceOf(ToolResult.Success::class.java) // fallback succeeded
        assertThat(first.summary).contains("step 0 (tap): expect_timeout")
        assertThat(first.summary).contains("#toolbar")
        assertThat(first.summary).contains("seen ids #home_feed,#avatar")
        assertThat(goals).hasSize(1)

        val sp = store.items[p.id]!!
        assertThat(sp.status).isEqualTo(PlaybookStatus.STALE)
        assertThat(sp.flaggedVersion).isEqualTo(150L)
        val rr = repo.pullRelearn()
        assertThat(rr).containsExactly(RelearnRequest(PKG, 150L, p.id, 0, "expect_timeout", rr.single().createdAt))
        assertThat(repo.pullRelearn()).isEmpty() // delivered once

        // Stale on this version → not replayed again; straight to the fallback (or a failure when not allowed).
        driver.taps.clear()
        val second = runner.run(p.id, mapOf("query" to "cats"), allowFallback = false)
        assertThat(second).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(second.summary).contains("stale")
        assertThat(driver.taps).isEmpty()
        assertThat(goals).hasSize(1)
    }

    @Test
    fun `app update outside the range makes playbooks unverified once and queues relearn`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = PlaybookFixtures.searchPlaybook(versionMin = 100, versionMax = 200)
        store.items[p.id] = StoredPlaybook(p)
        repo.onAppVersion(PKG, 300L)
        assertThat(store.items[p.id]!!.status).isEqualTo(PlaybookStatus.UNVERIFIED)
        assertThat(repo.pullRelearn().single().reason).isEqualTo("version_update")
        repo.onAppVersion(PKG, 300L)
        assertThat(repo.pullRelearn()).isEmpty()
        assertThat(PlaybookPolicy.effectiveStatus(store.items[p.id]!!, 300L)).isEqualTo(PlaybookStatus.UNVERIFIED)
        repo.onAppVersion(PKG, 150L)
        assertThat(store.items[p.id]!!.status).isEqualTo(PlaybookStatus.OK)
    }

    @Test
    fun `failure description carries ids only`() {
        val f = StepFailure(2, "tap", FailureKind.TARGET_NOT_FOUND, listOf("go_button"), listOf("Search"), listOf("a", "b"), PKG, 0.3)
        val d = f.describe()
        assertThat(d).contains("#go_button")
        assertThat(d).doesNotContain("Search") // expected strings are counted, not echoed
        assertThat(d).contains("best locator score 0.30")
    }
}
