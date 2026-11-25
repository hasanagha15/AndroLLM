package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.controller.ScreenNode
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.remote.UtteranceFastPaths
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Regression tests for the Opus gate of branch N (reviews/N-opus.md). P1–P6
 * are the reviewer's probes, inverted: they now assert the fixed behaviour.
 */
class PlaybookGateRegressionTest {

    // ── B1 / P1: no ui_run fallback under a remote origin ───────────────────

    @Test
    fun `P1 remote origin - mismatch never falls back to ui_run, returns a failure and a relearn record`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = PlaybookFixtures.searchPlaybook()
        store.items[p.id] = StoredPlaybook(p, source = "jarvis")
        val driver = FakeDriver { NodeSnapshot(PKG, listOf(node(0, "home_feed"))) }
        val goals = mutableListOf<String>()
        val runner = PlaybookRunner(repo, driver, { 150L }, { "Example Video" }, fallback = { g -> goals += g; FallbackOutcome(true, "done") })

        val r = withContext(RemoteToolOrigin()) { runner.run(p.id, mapOf("query" to "cats"), allowFallback = true) }
        assertThat(goals).isEmpty()
        assertThat(r).isInstanceOf(ToolResult.Failure::class.java)
        assertThat((r as ToolResult.Failure).retryable).isFalse()
        assertThat(r.summary).contains("no ui_run fallback")
        assertThat(repo.pullRelearn().single().reason).isEqualTo("expect_timeout")

        // Stale afterwards: a second remote call does not replay and still has no fallback.
        val again = withContext(RemoteToolOrigin()) { runner.run(p.id, mapOf("query" to "cats"), allowFallback = true) }
        assertThat(again.summary).contains("no ui_run fallback")
        assertThat(goals).isEmpty()
    }

    // ── B2 / P2: positional-only taps ───────────────────────────────────────

    @Test
    fun `P2 bounds-only tap is rejected at import and never tapped at runtime`() = runTest {
        val json = PlaybookFixtures.bundleJson(
            """{"id":"com.example.chat/go","package":"com.example.chat","name":"go","description":"Go",
               "versionMin":1,"versionMax":9,
               "steps":[{"op":"tap","locators":[{"kind":"bounds","bounds":[0.8,0.85,1.0,1.0],"weight":2.0}]}]}"""
        )
        val r = PlaybookValidator.parseBundle(json, emptySet())
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected.single().reason).contains("id, desc or text locator")

        // Runtime (playbook built directly, bypassing the importer): an unlabeled send icon
        // matched only by position (+ an id locator that does not match) is not resolved.
        val p = Playbook(
            id = "com.example.chat/go", packageName = "com.example.chat", name = "go", description = "Go",
            versionMin = 1, versionMax = 9,
            steps = listOf(PlaybookStep(op = "tap", timeoutMs = 500, locators = listOf(
                Locator("id", "send_fab"), Locator("bounds", bounds = listOf(0.8, 0.85, 1.0, 1.0), weight = 2.0)
            )))
        )
        val screen = NodeSnapshot("com.example.chat", listOf(
            ScreenNode(0, null, null, null, "android.widget.ImageButton", 0, true, false, 0.85f, 0.9f, 0.95f, 0.98f)
        ))
        val driver = FakeDriver { screen }
        val res = PlaybookEngine(driver).run(p, SlotValues.EMPTY, strict = false)
        assertThat(res.success).isFalse()
        assertThat(driver.taps).isEmpty()
    }

    // ── B3: atlas-sensitive (payment-scheme) apps ───────────────────────────

    @Test
    fun `B3 atlas-sensitive apps are refused at import, list and run`() = runTest {
        val json = PlaybookFixtures.bundleJson(
            """{"id":"com.example.cashapp/send","package":"com.example.cashapp","name":"send","description":"Open home",
               "versionMin":1,"versionMax":9,"steps":[{"op":"back"}]}"""
        )
        val facts: (String) -> AppFacts? = { AppFacts(label = "Cashy", sensitive = true) }
        val r = PlaybookValidator.parseBundle(json, emptySet(), null, facts)
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected.single().reason).contains("App Atlas marks it sensitive")

        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = Playbook(
            id = "com.example.cashapp/send", packageName = "com.example.cashapp", name = "send", description = "Open home",
            versionMin = 1, versionMax = 9, steps = listOf(PlaybookStep(op = "back"))
        )
        store.items[p.id] = StoredPlaybook(p)
        assertThat(repo.forPackage(p.packageName, "Cashy", atlasSensitive = true)).isEmpty()

        val driver = FakeDriver { NodeSnapshot(p.packageName, listOf(node(0, "x", pkg = p.packageName))) }
        val runner = PlaybookRunner(repo, driver, { 5L }, { "Cashy" }, fallback = null, atlasSensitive = { true })
        val out = runner.run(p.id, emptyMap(), allowFallback = true)
        assertThat(out).isInstanceOf(ToolResult.Failure::class.java)
        assertThat(out.summary).contains("protected app")
        assertThat(driver.taps).isEmpty()
    }

    // ── P3: compose / share links via custom schemes, foreign https hosts ───

    @Test
    fun `P3 custom-scheme compose links and foreign https hosts are rejected`() {
        val facts: (String) -> AppFacts? = { AppFacts(webHosts = setOf("chat.example.com")) }
        for (uri in listOf(
            "twitter://post?message={q}", "line://msg/text/{q}", "fb-messenger://share?link={q}",
            "https://evil.example.com/c?d={q}", "examplechat://open?text={q}", "examplepay://transfer/{q}"
        )) {
            val json = PlaybookFixtures.bundleJson(
                """{"id":"com.example.chat/go","package":"com.example.chat","name":"go","description":"Go",
                   "params":[{"name":"q"}],"versionMin":1,"versionMax":9,
                   "steps":[{"op":"open_deeplink","uri":"$uri"}]}"""
            )
            assertThat(PlaybookValidator.parseBundle(json, emptySet(), null, facts).accepted).isEmpty()
        }
        for (uri in listOf("https://chat.example.com/search?q={q}", "examplechat://search?q={q}")) {
            val json = PlaybookFixtures.bundleJson(
                """{"id":"com.example.chat/go","package":"com.example.chat","name":"go","description":"Go",
                   "params":[{"name":"q"}],"versionMin":1,"versionMax":9,
                   "steps":[{"op":"open_deeplink","uri":"$uri"}]}"""
            )
            assertThat(PlaybookValidator.parseBundle(json, emptySet(), null, facts).accepted).hasSize(1)
        }
    }

    // ── P4: relearn page fits the result and only delivered what fit ────────

    @Test
    fun `P4 relearn result stays under 8000 chars and undelivered records come next time`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        repeat(60) { i ->
            store.addRelearn(RelearnRequest("com.example.longpackagename.app$i", 1520000L + i, "com.example.longpackagename.app$i/search_action", 3, "expect_timeout", 1795000000000L))
        }
        val seen = HashSet<String>()
        var rounds = 0
        while (true) {
            val r = PlaybookSyncHandler.relearnResult(repo, 50)
            assertThat(r.text.length).isLessThan(8000)
            val obj = Json.parseToJsonElement(r.text).jsonObject
            val ids = obj["requests"]!!.jsonArray.map { it.jsonObject["playbookId"].toString() }
            seen += ids
            rounds++
            if (obj["more"].toString() != "true") break
            assertThat(rounds).isLessThan(10)
        }
        assertThat(seen).hasSize(60)
        assertThat(rounds).isGreaterThan(1)
    }

    // ── P5: strict uniqueness ───────────────────────────────────────────────

    @Test
    fun `P5 strict mode - a clickable plus non-clickable tie is not unique`() {
        val locs = listOf(Locator("text", "OK"))
        val nodes = listOf(node(0, text = "OK", clickable = false), node(1, text = "OK", clickable = true))
        assertThat(LocatorResolver.resolve(locs, nodes, strict = true)).isNull()
        assertThat(LocatorResolver.resolve(locs, nodes, strict = false)!!.node.index).isEqualTo(1)
    }

    // ── P6: re-import keeps state; remote does not replace a file import ────

    @Test
    fun `P6 re-import keeps STALE, remote never silently replaces the file-imported playbook`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val json = PlaybookFixtures.bundleJson(PlaybookFixtures.VALID_PLAYBOOK_JSON)
        val id = "com.example.video/search"
        repo.importJson(json, "file", null)
        repo.recordMismatch(id, 150L, StepFailure(0, "tap", FailureKind.TARGET_NOT_FOUND))
        repo.importJson(json, "file", null)
        assertThat(store.items[id]!!.status).isEqualTo(PlaybookStatus.STALE)

        // Remote, changed but not newer → loses.
        val changed = json.replace("Search videos", "Search clips")
        val r = repo.importJson(changed, "jarvis", null)
        assertThat(r.accepted).isEmpty()
        assertThat(r.rejected.single().reason).contains("kept the file-imported version")
        assertThat(store.items[id]!!.source).isEqualTo("file")
        assertThat(store.items[id]!!.playbook.description).isEqualTo("Search videos")

        // Remote, newer (learnedAt) and with a postcondition → replaces, state reset.
        val newer = changed.replace("\"versionMin\":100", "\"learnedAt\":5,\"versionMin\":100")
        assertThat(repo.importJson(newer, "jarvis", null).accepted).hasSize(1)
        assertThat(store.items[id]!!.source).isEqualTo("jarvis")
        assertThat(store.items[id]!!.status).isEqualTo(PlaybookStatus.OK)
    }

    // ── cards: re-verify after approval, name the playbook ──────────────────

    @Test
    fun `screen change while the card is open means no tap, and the card names the playbook`() = runTest {
        val p = PlaybookValidator.withComputedRisk(PlaybookFixtures.sendPlaybook())
        var screen = NodeSnapshot(PKG, listOf(node(0, "comment_edit", editable = true), node(1, "send_button", text = "Send")))
        val driver = FakeDriver { screen }
        // While the owner looks at the card, another chat opens: the Send button is now a different element.
        driver.onConfirm = { screen = NodeSnapshot(PKG, listOf(node(0, "comment_edit", editable = true), node(2, "send_button", text = "Send to Bob"))) }
        val r = PlaybookEngine(driver).run(p, (PlaybookSlots.fill(p, mapOf("text" to "hi")) as SlotFill.Ok).slots, strict = false)
        assertThat(r.success).isFalse()
        assertThat(r.failure!!.kind).isEqualTo(FailureKind.SCREEN_CHANGED)
        assertThat(driver.taps).isEmpty()
        assertThat(driver.confirmations.single()).contains(p.id)
        assertThat(driver.confirmations.single()).contains("Post a comment")
    }

    @Test
    fun `relearn records are queued once - postcondition does not overwrite a mismatch`() = runTest {
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = PlaybookFixtures.searchPlaybook()
        store.items[p.id] = StoredPlaybook(p)
        repo.recordMismatch(p.id, 150L, StepFailure(1, "tap", FailureKind.TARGET_NOT_FOUND))
        repo.recordUnverified(p.id, 150L)
        assertThat(repo.pullRelearn().single().reason).isEqualTo("target_not_found")
        repo.recordUnverified(p.id, 150L)
        assertThat(repo.pullRelearn()).isEmpty()
    }

    // ── fast path registry: owner turns only ────────────────────────────────

    @Test
    fun `fast path is never consulted for remote turns`() = runTest {
        val h = UtteranceFastPaths.Handler { u -> if (u == "новая заметка") "✓ New note" else null }
        UtteranceFastPaths.register(h)
        try {
            assertThat(UtteranceFastPaths.tryHandle("новая заметка")).isEqualTo("✓ New note")
            assertThat(UtteranceFastPaths.tryHandle("что-то другое")).isNull()
            assertThat(withContext(RemoteToolOrigin()) { UtteranceFastPaths.tryHandle("новая заметка") }).isNull()
        } finally {
            UtteranceFastPaths.unregister(h)
        }
    }
}
