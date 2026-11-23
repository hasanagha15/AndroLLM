package io.androllm.core.appatlas

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.index.AtlasIndexer
import io.androllm.core.appatlas.learn.LearnedPathPolicy
import io.androllm.core.appatlas.learn.PathSessionTracker
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.model.PathStep
import io.androllm.core.appatlas.search.Concepts
import io.androllm.core.appatlas.sync.AtlasSynchronizer
import io.androllm.core.tools.uistep.UiStep
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncAndLearnedPathTest {

    private fun path(pkg: String, version: Long, key: String = "search") =
        LearnedPath(pkg, key, version, listOf(PathStep("tap", id = "search_button")), verified = true)

    @Test
    fun `sync indexes new apps, skips unchanged, removes uninstalled and excluded`() = runTest {
        val src = FakePackageSource(
            packages = mutableListOf(
                Fixtures.basics("com.example.a", "A"),
                Fixtures.basics("com.example.b", "B"),
                Fixtures.basics("io.androllm.app", "Self")
            )
        )
        val store = InMemoryAtlasStore()
        val sync = AtlasSynchronizer(src, store, AtlasIndexer(src))

        val first = sync.sync(exclusions = emptySet())
        assertThat(first.indexed).isEqualTo(2) // own package never indexed
        assertThat(store.apps.keys).containsExactly("com.example.a", "com.example.b")

        val second = sync.sync(exclusions = emptySet())
        assertThat(second.indexed).isEqualTo(0)
        assertThat(second.unchanged).isEqualTo(2)

        src.packages.removeAll { it.packageName == "com.example.b" }
        val third = sync.sync(exclusions = setOf("com.example.a"))
        assertThat(third.removed).isEqualTo(2)
        assertThat(store.apps).isEmpty()
    }

    @Test
    fun `app update re-indexes and flags learned paths stale`() = runTest {
        val src = FakePackageSource(packages = mutableListOf(Fixtures.basics("com.example.a", "A", version = 1)))
        val store = InMemoryAtlasStore()
        val sync = AtlasSynchronizer(src, store, AtlasIndexer(src))
        sync.sync(emptySet())
        store.upsertPath(path("com.example.a", 1))

        src.packages[0] = Fixtures.basics("com.example.a", "A", version = 2)
        val report = sync.sync(emptySet())

        assertThat(report.indexed).isEqualTo(1)
        assertThat(report.stalePaths).isEqualTo(1)
        assertThat(store.apps.getValue("com.example.a").versionCode).isEqualTo(2)
        val p = store.paths("com.example.a").single()
        assertThat(p.stale).isTrue() // kept as a hint, not deleted
        assertThat(p.versionCode).isEqualTo(1)
    }

    @Test
    fun `same-version change from broadcast is re-indexed only when requested`() = runTest {
        val src = FakePackageSource(packages = mutableListOf(Fixtures.basics("com.example.a", "A")))
        val store = InMemoryAtlasStore()
        val sync = AtlasSynchronizer(src, store, AtlasIndexer(src))
        sync.sync(emptySet())
        assertThat(sync.sync(emptySet(), only = setOf("com.example.a")).indexed).isEqualTo(1)
        assertThat(sync.sync(emptySet(), force = true).indexed).isEqualTo(1)
    }

    @Test
    fun `version change policy keeps stale hints`() {
        val paths = listOf(path("p", 1), path("p", 2, key = "send"))
        val out = LearnedPathPolicy.onVersionChange(paths, 2)
        assertThat(out.first { it.taskKey == "search" }.stale).isTrue()
        assertThat(out.first { it.taskKey == "send" }.stale).isFalse()
        assertThat(LearnedPathPolicy.render(out[0], 2)).contains("stale")
    }

    private val entry = AppAtlasEntry(
        packageName = "com.example.chat",
        label = "Chat",
        versionCode = 7,
        uiIds = listOf("compose_button", "send"),
        uiStrings = listOf("New chat", "Send")
    )

    @Test
    fun `sanitize never stores screen text or foreign ids`() {
        val contact = UiStep("com.example.chat", UiStep.Op.TAP, null, "Aunt Mary", true)
        assertThat(LearnedPathPolicy.sanitize(contact, entry)).isNull()

        val withId = UiStep("com.example.chat", UiStep.Op.TAP, "com.example.chat:id/row_contact", "Aunt Mary", true)
        assertThat(LearnedPathPolicy.sanitize(withId, entry)).isEqualTo(PathStep("tap", id = "row_contact", label = null))

        val appLabel = UiStep("com.example.chat", UiStep.Op.TAP, "com.other:id/x", "send", true)
        assertThat(LearnedPathPolicy.sanitize(appLabel, entry)).isEqualTo(PathStep("tap", id = null, label = "send"))

        val type = UiStep("com.example.chat", UiStep.Op.TYPE, "com.example.chat:id/input", "hello aunt", true)
        assertThat(LearnedPathPolicy.sanitize(type, entry)).isEqualTo(PathStep("type", id = "input"))

        val scroll = UiStep("com.example.chat", UiStep.Op.SCROLL, null, "down", true)
        assertThat(LearnedPathPolicy.sanitize(scroll, entry)).isEqualTo(PathStep("scroll", arg = "down"))
    }

    @Test
    fun `successful ui_run becomes a verified path keyed by concepts only`() {
        var now = 0L
        val t = PathSessionTracker { now }
        t.begin("com.example.chat", 7, null)
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, "com.example.chat:id/compose_button", "New chat", true), entry)
        now += 1000
        t.onStep(UiStep("com.example.chat", UiStep.Op.TYPE, "com.example.chat:id/input", null, true), entry)
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, "com.example.chat:id/send", "Send", true), entry)
        val out = t.onTaskFinished("send a message to Aunt Mary saying I'm late", true) { entry }

        val p = out.single()
        assertThat(p.verified).isTrue()
        assertThat(p.taskKey).isEqualTo("message+send")
        assertThat(p.versionCode).isEqualTo(7)
        assertThat(p.steps.map { it.op }).containsExactly("tap", "type", "tap").inOrder()
        val serialized = p.toString()
        assertThat(serialized).doesNotContain("Aunt")
        assertThat(serialized).doesNotContain("late")
    }

    @Test
    fun `failed or unreplayable tasks are not recorded`() {
        val t = PathSessionTracker { 0L }
        t.begin("com.example.chat", 7, "search")
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, null, "send", false), entry)
        assertThat(t.onTaskFinished("search", true) { entry }).isEmpty()

        t.begin("com.example.chat", 7, "search")
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, null, "Some contact", true), entry)
        assertThat(t.onTaskFinished("search", true) { entry }).isEmpty()

        t.begin("com.example.chat", 7, "search")
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, null, "send", true), entry)
        assertThat(t.onTaskFinished("search", false) { entry }).isEmpty()

        // No action concept in the goal → nothing stored (we never keep the user's words).
        t.begin("com.example.chat", 7, null)
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, null, "send", true), entry)
        assertThat(t.onTaskFinished("do the thing for Mary", true) { entry }).isEmpty()
    }

    @Test
    fun `sensitive apps are never recorded`() {
        val t = PathSessionTracker { 0L }
        val bank = entry.copy(packageName = "com.example.bank", sensitive = true)
        t.begin("com.example.bank", 1, "pay")
        t.onStep(UiStep("com.example.bank", UiStep.Op.TAP, "com.example.bank:id/send", "Send", true), bank)
        assertThat(t.onTaskFinished("pay the bill", true) { bank }).isEmpty()

        t.begin("com.whatsapp", 1, "send")
        t.onStep(UiStep("com.whatsapp", UiStep.Op.TAP, "com.whatsapp:id/send", "Send", true), null)
        assertThat(t.onTaskFinished("send message", true) { null }).isEmpty()
    }

    @Test
    fun `model-driven sequence is committed unverified when the app changes`() {
        val t = PathSessionTracker { 0L }
        t.pendingTaskKey = Concepts.taskKey("telegram new chat")
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, "com.example.chat:id/compose_button", null, true), entry)
        t.onStep(UiStep("com.example.chat", UiStep.Op.TAP, "com.example.chat:id/send", null, true), entry)
        val out = t.onStep(UiStep("com.example.other", UiStep.Op.TAP, "com.example.other:id/x", null, true), null)
        assertThat(out.single().verified).isFalse()
        assertThat(out.single().taskKey).isEqualTo("message+new")
    }

    @Test
    fun `merge counts repeats and protects verified paths`() {
        val v = path("p", 1).copy(successCount = 2)
        val same = path("p", 2)
        val merged = LearnedPathPolicy.merge(v, same)
        assertThat(merged.successCount).isEqualTo(3)
        assertThat(merged.versionCode).isEqualTo(2)
        val unverifiedOther = LearnedPath("p", "search", 1, listOf(PathStep("tap", id = "other")), verified = false)
        assertThat(LearnedPathPolicy.merge(v, unverifiedOther)).isEqualTo(v)
        assertThat(LearnedPathPolicy.merge(v.copy(stale = true), unverifiedOther)).isEqualTo(unverifiedOther)
    }
}
