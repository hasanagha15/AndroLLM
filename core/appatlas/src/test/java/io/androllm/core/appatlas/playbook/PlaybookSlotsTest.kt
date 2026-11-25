package io.androllm.core.appatlas.playbook

import com.google.common.truth.Truth.assertThat
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookFixtures.node
import io.androllm.core.appatlas.store.PlaybookRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import timber.log.Timber

class PlaybookSlotsTest {

    private val secret = "Секретный запрос 42 котики"

    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines += message }
    }

    @After fun tearDown() = Timber.uprootAll()

    private fun okFill(p: Playbook, args: Map<String, String?>) = (PlaybookSlots.fill(p, args) as SlotFill.Ok).slots

    @Test
    fun `fills, validates and renders slots`() {
        val p = PlaybookFixtures.searchPlaybook().copy(
            params = listOf(
                PlaybookParam("query"),
                PlaybookParam("count", type = "int", required = false),
                PlaybookParam("mode", type = "enum", values = listOf("Video", "Shorts"), required = false)
            )
        )
        val s = okFill(p, mapOf("query" to "  cats  ", "count" to "3", "mode" to "shorts"))
        assertThat(s["query"]).isEqualTo("cats")
        assertThat(s["mode"]).isEqualTo("Shorts")
        assertThat(PlaybookSlots.renderText("find {query} x{count}", s)).isEqualTo("find cats x3")
        assertThat(PlaybookSlots.fill(p, emptyMap())).isInstanceOf(SlotFill.Error::class.java)
        assertThat((PlaybookSlots.fill(p, mapOf("query" to "a", "count" to "x")) as SlotFill.Error).reason).contains("integer")
        assertThat((PlaybookSlots.fill(p, mapOf("query" to "a", "mode" to "live")) as SlotFill.Error).reason).contains("one of")
        // A line break could submit a field: refused; the error never echoes the value.
        val err = PlaybookSlots.fill(p, mapOf("query" to "hi\nsend")) as SlotFill.Error
        assertThat(err.reason).contains("single line")
        assertThat(err.reason).doesNotContain("hi")
        assertThat(PlaybookSlots.fill(p, mapOf("query" to "x".repeat(201)))).isInstanceOf(SlotFill.Error::class.java)
    }

    @Test
    fun `uri slots are encoded and cannot change scheme or host`() {
        val p = PlaybookFixtures.searchPlaybook()
        val s = okFill(p, mapOf("query" to "a b&c=d/../x"))
        val uri = PlaybookSlots.renderUri("https://video.example.com/results?q={query}", s)!!
        assertThat(uri).startsWith("https://video.example.com/results?q=")
        assertThat(uri).doesNotContain(" ")
        assertThat(uri).doesNotContain("&c=")
        assertThat(PlaybookSlots.renderUri("examplevideo://search?q={query}", okFill(p, mapOf("query" to "@evil.com")))).startsWith("examplevideo://search?q=%40evil.com")
    }

    @Test
    fun `slot values are redacted in toString`() {
        val s = okFill(PlaybookFixtures.searchPlaybook(), mapOf("query" to secret))
        assertThat(s.toString()).doesNotContain(secret)
        assertThat(s.toString()).contains("query=<redacted>")
    }

    @Test
    fun `typed slot value is typed but never persisted, logged or echoed`() = runTest {
        val tree = CapturingTree()
        Timber.plant(tree)
        val store = MemoryPlaybookStore()
        val repo = PlaybookRepository(store) { emptySet() }
        val p = PlaybookFixtures.searchPlaybook()
        store.items[p.id] = StoredPlaybook(p)
        val screen = NodeSnapshot(PKG, listOf(node(0, "toolbar"), node(1, "search_button"), node(2, "search_edit", editable = true), node(3, "go_button")))
        val driver = FakeDriver { screen }
        val runner = PlaybookRunner(repo, driver, { 150L }, { "Example Video" }, fallback = null)

        val ok = runner.run(p.id, mapOf("query" to secret), allowFallback = false)
        assertThat(driver.typed).containsExactly(secret)               // it was typed…
        assertThat(ok.summary).doesNotContain(secret)                   // …not echoed
        // Postcondition (results_list) is not on screen: a relearn request is queued — ids only.
        assertThat(store.relearn.values.map { it.first.reason }).containsExactly("postcondition")

        // Mismatch path too: nothing on screen → stale + relearn, still no secret anywhere.
        driver.screen = { NodeSnapshot(PKG, listOf(node(0, "other"))) }
        val failed = runner.run(p.id, mapOf("query" to secret), allowFallback = false)
        assertThat(failed.summary).doesNotContain(secret)

        val persisted = store.items.values.joinToString { it.toString() } + store.relearn.values.joinToString { it.toString() }
        assertThat(persisted).doesNotContain(secret)
        assertThat(tree.lines.joinToString("\n")).doesNotContain(secret)
        // The Room row is built from the Playbook only (no slot field exists).
        assertThat(PlaybookRow::class.java.declaredFields.map { it.name }).containsNoneOf("slots", "args", "query")
    }
}
