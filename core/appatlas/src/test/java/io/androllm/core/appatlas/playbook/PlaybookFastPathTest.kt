package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.remote.UtteranceFastPaths
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * Branch Q: zero-LLM fast path follow-ups from the N gate (reviews/N-opus.md,
 * revision 2, recommendations 1–3):
 * - only file-imported or device-verified playbooks are routed;
 * - a replay that stops part-way hands the model a structured note;
 * - risky playbooks and the "Fast playbook commands" switch.
 */
class PlaybookFastPathTest {

    private val store = MemoryPlaybookStore()
    private val repo = PlaybookRepository(store) { emptySet() }
    private var searched = false
    private var showResults = true
    private var showGoButton = true
    private val driver = FakeDriver {
        val nodes = mutableListOf(node(0, "toolbar"), node(1, "search_button"), node(2, "search_edit", editable = true))
        if (showGoButton) nodes += node(3, "go_button")
        if (searched && showResults) nodes += node(4, "results_list")
        NodeSnapshot(PKG, nodes)
    }.also { d -> d.onTap = { ref -> if (ref.viewId?.endsWith("go_button") == true) searched = true } }
    private val runner = PlaybookRunner(repo, driver, { 150L }, { "Example Video" }, fallback = null)
    private var atlasOn = true
    private var fastOn = true

    /** Routable = the stored library as-is, so the fast path's OWN guards are what is tested. */
    private val fastPath = PlaybookFastPath(
        routable = { repo.all() },
        runPlaybook = { sp, slots -> runner.runResolved(sp, slots, allowFallback = false) },
        lookup = { repo.get(it) },
        atlasEnabled = { atlasOn },
        fastCommandsEnabled = { fastOn }
    )

    private fun stored(p: Playbook, source: String, verified: Int = 0) {
        store.items[p.id] = StoredPlaybook(p, source = source, verifiedCount = verified)
    }

    private val utterance = "search video for cats"

    // ── rec 1: trust ────────────────────────────────────────────────────────

    @Test
    fun `jarvis playbook does not take over the owner's command until verified on the device`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        stored(p, PlaybookRepository.SOURCE_JARVIS)

        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        assertThat(driver.taps).isEmpty()

        // The model runs it once via playbook_run; the postcondition is seen → verified.
        assertThat(runner.run(p.id, mapOf("query" to "cats"), allowFallback = false))
            .isInstanceOf(io.androllm.core.tools.api.ToolResult.Success::class.java)
        assertThat(store.items[p.id]!!.verifiedCount).isEqualTo(1)

        searched = false
        driver.taps.clear()
        val out = fastPath.handle(utterance)
        assertThat(out.reply).isEqualTo("✓ Search videos")
        assertThat(driver.taps).isNotEmpty()
    }

    @Test
    fun `a success without the postcondition seen does not verify a jarvis playbook`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        stored(p, PlaybookRepository.SOURCE_JARVIS)
        showResults = false
        runner.run(p.id, mapOf("query" to "cats"), allowFallback = false)
        assertThat(store.items[p.id]!!.successCount).isEqualTo(1)
        assertThat(store.items[p.id]!!.verifiedCount).isEqualTo(0)
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
    }

    @Test
    fun `a playbook without a postcondition is never verified`() = runTest {
        val p = PlaybookFixtures.searchPlaybook(postcondition = null)
        stored(p, PlaybookRepository.SOURCE_JARVIS)
        runner.run(p.id, mapOf("query" to "cats"), allowFallback = false)
        assertThat(store.items[p.id]!!.verifiedCount).isEqualTo(0)
    }

    @Test
    fun `file-imported playbook is routed`() = runTest {
        stored(PlaybookFixtures.searchPlaybook(), PlaybookRepository.SOURCE_FILE)
        assertThat(fastPath.handle(utterance).reply).isEqualTo("✓ Search videos")
    }

    @Test
    fun `a changed re-import drops the verification, an unchanged one keeps it`() = runTest {
        val json = PlaybookFixtures.bundleJson(PlaybookFixtures.VALID_PLAYBOOK_JSON)
        val id = "com.example.video/search"
        repo.importJson(json, PlaybookRepository.SOURCE_JARVIS, null)
        repo.recordSuccess(id, verified = true)
        repo.importJson(json, PlaybookRepository.SOURCE_JARVIS, null)
        assertThat(store.items[id]!!.verifiedCount).isEqualTo(1)
        repo.importJson(json.replace("Search videos", "Search clips"), PlaybookRepository.SOURCE_JARVIS, null)
        assertThat(store.items[id]!!.verifiedCount).isEqualTo(0)
        assertThat(PlaybookPolicy.fastPathTrusted(store.items[id]!!)).isFalse()
    }

    @Test
    fun `catalog filter - trust, risk, protection and status`() {
        val entry = AppAtlasEntry(packageName = PKG, label = "Example Video", versionCode = 150)
        val p = PlaybookFixtures.searchPlaybook()
        fun ok(sp: StoredPlaybook, e: AppAtlasEntry? = entry) = PlaybookCatalog.isRoutable(sp, e, emptySet())

        assertThat(ok(StoredPlaybook(p, source = "file"))).isTrue()
        assertThat(ok(StoredPlaybook(p, source = "jarvis"))).isFalse()
        assertThat(ok(StoredPlaybook(p, source = "jarvis", verifiedCount = 1))).isTrue()
        assertThat(ok(StoredPlaybook(p.copy(risk = Playbook.RISK_CONFIRM), source = "file"))).isFalse()
        assertThat(ok(StoredPlaybook(p, source = "file"), entry.copy(sensitive = true))).isFalse()
        assertThat(ok(StoredPlaybook(p, source = "file"), null)).isFalse()
        assertThat(ok(StoredPlaybook(p, source = "file", status = PlaybookStatus.STALE, flaggedVersion = 150))).isFalse()
        assertThat(ok(StoredPlaybook(p.copy(triggers = emptyList()), source = "file"))).isFalse()
    }

    // ── rec 3: risky playbooks and the setting ──────────────────────────────

    @Test
    fun `risky playbook is never run by the fast path even if routed`() = runTest {
        val risky = PlaybookFixtures.searchPlaybook().copy(risk = Playbook.RISK_CONFIRM)
        stored(risky, PlaybookRepository.SOURCE_FILE, verified = 3)
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        assertThat(driver.taps).isEmpty()
        assertThat(driver.typed).isEmpty()
        assertThat(driver.confirmations).isEmpty()
        // And the direct run entry refuses it too.
        val m = PlaybookRouter.match(utterance, listOf(risky))!!
        assertThat(fastPath.run(m)).isInstanceOf(io.androllm.core.tools.api.ToolResult.Failure::class.java)
        assertThat(driver.taps).isEmpty()
    }

    @Test
    fun `setting OFF disables the fast path`() = runTest {
        stored(PlaybookFixtures.searchPlaybook(), PlaybookRepository.SOURCE_FILE)
        fastOn = false
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        assertThat(fastPath.tryHandle(utterance)).isNull()
        assertThat(driver.taps).isEmpty()
        assertThat(driver.typed).isEmpty()
    }

    @Test
    fun `atlas OFF and remote turns disable the fast path`() = runTest {
        stored(PlaybookFixtures.searchPlaybook(), PlaybookRepository.SOURCE_FILE)
        atlasOn = false
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        atlasOn = true
        assertThat(withContext(RemoteToolOrigin()) { fastPath.handle(utterance) }).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        assertThat(driver.taps).isEmpty()
    }

    // ── rec 2: partial execution → note for the model ───────────────────────

    @Test
    fun `replay that stops mid-way hands the model a structured note without user text`() = runTest {
        val base = PlaybookFixtures.searchPlaybook()
        val p = base.copy(steps = base.steps.mapIndexed { i, s -> if (i == 2) s.copy(timeoutMs = 300) else s })
        stored(p, PlaybookRepository.SOURCE_FILE)
        showGoButton = false

        val out = fastPath.handle(utterance)

        assertThat(out.reply).isNull()
        val note = out.modelNote!!
        assertThat(note).contains("playbook com.example.video/search")
        assertThat(note).contains("Steps completed: 2 of 3 (tap, type)")
        assertThat(note).contains("Stopped at step 3 (tap), reason: target_not_found")
        assertThat(note).contains("check the current screen")
        // The slot value (the owner's words) never goes into the note.
        assertThat(driver.typed).containsExactly("cats")
        assertThat(note).doesNotContain("cats")
    }

    @Test
    fun `the registry passes the note through when no handler replied`() = runTest {
        val h = object : UtteranceFastPaths.Handler {
            override suspend fun tryHandle(utterance: String): String? = null
            override suspend fun handle(utterance: String) = UtteranceFastPaths.Outcome(modelNote = "NOTE")
        }
        UtteranceFastPaths.register(h)
        try {
            assertThat(UtteranceFastPaths.handle("anything")).isEqualTo(UtteranceFastPaths.Outcome(modelNote = "NOTE"))
            assertThat(UtteranceFastPaths.tryHandle("anything")).isNull()
            assertThat(withContext(RemoteToolOrigin()) { UtteranceFastPaths.handle("anything") })
                .isEqualTo(UtteranceFastPaths.Outcome.NONE)
        } finally {
            UtteranceFastPaths.unregister(h)
        }
    }

    @Test
    fun `no note when the replay never started (gone, not eligible, stale, protected)`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        assertThat(PlaybookFastPath.partialNote(p, kotlinx.serialization.json.buildJsonObject { })).isNull()
        // Stale on the installed version: refused before any step — the model answers without a note.
        store.items[p.id] = StoredPlaybook(p, source = "file", status = PlaybookStatus.STALE, flaggedVersion = 150)
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        val gone = PlaybookFastPath(
            routable = { listOf(StoredPlaybook(p, source = "file")) },
            runPlaybook = { sp, slots -> runner.runResolved(sp, slots, allowFallback = false) },
            lookup = { null },
            atlasEnabled = { true },
            fastCommandsEnabled = { true }
        )
        assertThat(gone.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
        assertThat(driver.taps).isEmpty()
    }

    @Test
    fun `note after a failure at step 0 does not claim nothing happened (gate Q N1)`() {
        val p = PlaybookFixtures.searchPlaybook()
        val data = kotlinx.serialization.json.buildJsonObject {
            put("failed_step", kotlinx.serialization.json.JsonPrimitive(0))
            put("failed_op", kotlinx.serialization.json.JsonPrimitive("tap"))
            put("reason", kotlinx.serialization.json.JsonPrimitive("expect_timeout"))
            put("steps_done", kotlinx.serialization.json.JsonPrimitive(0))
            put("steps_total", kotlinx.serialization.json.JsonPrimitive(3))
        }
        val note = PlaybookFastPath.partialNote(p, data)!!
        assertThat(note).contains("Steps completed: 0 of 3")
        assertThat(note).contains("Stopped at step 1 (tap), reason: expect_timeout")
        assertThat(note).contains("the app may have been opened")
        assertThat(note).doesNotContain("Nothing was done")
    }

    @Test
    fun `a remote (Jarvis) playbook_run never earns fast-path trust (gate Q N2)`() = runTest {
        val p = PlaybookFixtures.searchPlaybook()
        stored(p, PlaybookRepository.SOURCE_JARVIS)
        val r = withContext(RemoteToolOrigin()) { runner.run(p.id, mapOf("query" to "cats"), allowFallback = false) }
        assertThat(r).isInstanceOf(io.androllm.core.tools.api.ToolResult.Success::class.java)
        assertThat(store.items[p.id]!!.successCount).isEqualTo(1)
        assertThat(store.items[p.id]!!.verifiedCount).isEqualTo(0)
        assertThat(fastPath.handle(utterance)).isEqualTo(UtteranceFastPaths.Outcome.NONE)
    }
}
