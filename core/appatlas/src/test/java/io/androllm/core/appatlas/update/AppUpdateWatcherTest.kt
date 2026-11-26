package io.androllm.core.appatlas.update

import com.google.common.truth.Truth.assertThat
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.playbook.MemoryPlaybookStore
import io.androllm.core.appatlas.playbook.PlaybookCatalog
import io.androllm.core.appatlas.playbook.PlaybookFixtures
import io.androllm.core.appatlas.playbook.PlaybookFixtures.PKG
import io.androllm.core.appatlas.playbook.PlaybookPolicy
import io.androllm.core.appatlas.playbook.PlaybookRepository
import io.androllm.core.appatlas.playbook.PlaybookStatus
import io.androllm.core.appatlas.playbook.StoredPlaybook
import io.androllm.core.tools.remote.JarvisEventSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

/** Branch R1: AppUpdateWatcher logic — synthetic packages only (com.example.*). */
class AppUpdateWatcherTest {

    private class FakeApps : InstalledApps {
        val versions = linkedMapOf<String, Long>()
        var seq = 0
        val changedLog = mutableListOf<Pair<Int, String>>() // (sequence after the change, pkg)
        var boot = 1
        val queried = mutableListOf<Int>()

        fun change(pkg: String, vc: Long?) {
            if (vc == null) versions.remove(pkg) else versions[pkg] = vc
            seq++
            changedLog += seq to pkg
        }

        override fun versionOf(packageName: String) = versions[packageName]
        override fun snapshot() = versions.toMap()
        override fun changedSince(sequence: Int): Pair<Int, List<String>>? {
            queried += sequence
            val pkgs = changedLog.filter { it.first > sequence }.map { it.second }.distinct()
            return if (pkgs.isEmpty()) null else seq to pkgs
        }
        override fun bootCount() = boot
        override val ownPackage = "com.example.androllm"
    }

    private class MemoryState : AppUpdateState {
        override var sequence = -1
        override var bootCount = -1
        private var map: Map<String, Long> = emptyMap()
        override fun known() = map
        override fun saveKnown(known: Map<String, Long>) { map = known.toMap() }
    }

    private class RecordingSink : JarvisEventSink {
        val events = mutableListOf<Pair<String, JsonObject>>()
        override fun publish(type: String, body: JsonObject) { events += type to body }
    }

    private val apps = FakeApps().apply {
        versions[PKG] = 150
        versions["com.example.notes"] = 7
        versions[ownPackage] = 1
    }
    private val state = MemoryState()
    private var now = 1_795_000_000_000L
    private val tracker = AppUpdateTracker(apps, state) { now }

    private val store = MemoryPlaybookStore()
    private val repo = PlaybookRepository(store) { setOf(BANK) }
    private val sink = RecordingSink()
    private val handler = AppUpdateHandler(repo, { it == BANK || it.contains("bank") }, sink)

    // ── tracker ─────────────────────────────────────────────────────────────

    @Test
    fun `first run records a baseline without reporting every installed app`() = runTest {
        assertThat(tracker.checkChanged()).isEmpty()
        assertThat(state.known()).containsExactly(PKG, 150L, "com.example.notes", 7L)
        assertThat(state.sequence).isEqualTo(0)
    }

    @Test
    fun `dedup by package and versionCode - broadcasts and the poll report one update once`() = runTest {
        tracker.checkChanged() // baseline
        apps.change(PKG, 151)

        // REMOVED(replacing) is filtered by the receiver; ADDED + REPLACED may still both arrive.
        val first = tracker.onPackageEvent(PKG)
        val second = tracker.onPackageEvent(PKG)
        val poll = tracker.checkChanged()

        assertThat(first).hasSize(1)
        assertThat(first[0].kind).isEqualTo(AppChange.Kind.UPDATED)
        assertThat(first[0].oldVersionCode).isEqualTo(150L)
        assertThat(first[0].newVersionCode).isEqualTo(151L)
        assertThat(second).isEmpty()
        assertThat(poll).isEmpty()

        // A NEW versionCode of the same package is a new change.
        apps.change(PKG, 152)
        assertThat(tracker.checkChanged().map { it.newVersionCode }).containsExactly(152L)
    }

    @Test
    fun `install and removal are reported, own package never`() = runTest {
        tracker.checkChanged()
        apps.change("com.example.maps", 3)
        apps.change("com.example.notes", null)
        apps.change(apps.ownPackage, 2)

        val changes = tracker.checkChanged().associateBy { it.packageName }
        assertThat(changes.keys).containsExactly("com.example.maps", "com.example.notes")
        assertThat(changes.getValue("com.example.maps").kind).isEqualTo(AppChange.Kind.ADDED)
        assertThat(changes.getValue("com.example.maps").oldVersionCode).isNull()
        assertThat(changes.getValue("com.example.notes").kind).isEqualTo(AppChange.Kind.REMOVED)
        assertThat(changes.getValue("com.example.notes").newVersionCode).isNull()
        assertThat(state.known()).doesNotContainKey("com.example.notes")
    }

    @Test
    fun `sequence number is persisted and survives a restart`() = runTest {
        tracker.checkChanged()
        apps.change(PKG, 151)
        apps.change("com.example.notes", 8)
        tracker.checkChanged()
        assertThat(state.sequence).isEqualTo(2)

        // "Process restart": a new tracker over the same persisted state asks from sequence 2.
        val restarted = AppUpdateTracker(apps, state) { now }
        apps.queried.clear()
        assertThat(restarted.checkChanged()).isEmpty()
        assertThat(apps.queried).containsExactly(2)

        apps.change(PKG, 152)
        val changes = restarted.checkChanged()
        assertThat(changes.map { it.packageName to it.newVersionCode }).containsExactly(PKG to 152L)
        assertThat(state.sequence).isEqualTo(3)
    }

    @Test
    fun `after a reboot the sequence restarts at 0`() = runTest {
        tracker.checkChanged()
        apps.change(PKG, 151)
        tracker.checkChanged()
        assertThat(state.sequence).isEqualTo(1)

        // Reboot: PackageManager's counter restarts; an update applied during boot.
        apps.boot = 2
        apps.seq = 0
        apps.changedLog.clear()
        apps.change(PKG, 160)
        apps.queried.clear()

        val changes = tracker.checkChanged()
        assertThat(apps.queried).containsExactly(0)
        assertThat(changes.map { it.newVersionCode }).containsExactly(160L)
        assertThat(state.bootCount).isEqualTo(2)
        assertThat(state.sequence).isEqualTo(1)
    }

    @Test
    fun `reset forgets the baseline`() = runTest {
        tracker.checkChanged()
        tracker.reset()
        assertThat(state.sequence).isEqualTo(-1)
        assertThat(state.known()).isEmpty()
        apps.change(PKG, 151)
        assertThat(tracker.checkChanged()).isEmpty() // fresh baseline, no burst
        assertThat(state.known()[PKG]).isEqualTo(151L)
    }

    // ── handler ─────────────────────────────────────────────────────────────

    private fun storePlaybooks() {
        val search = PlaybookFixtures.searchPlaybook()
        val other = search.copy(id = "$PKG/open_library", name = "open_library", triggers = listOf("open video library"))
        store.items[search.id] = StoredPlaybook(search, source = PlaybookRepository.SOURCE_FILE, verifiedCount = 2)
        store.items[other.id] = StoredPlaybook(other, source = PlaybookRepository.SOURCE_JARVIS, verifiedCount = 1)
    }

    @Test
    fun `an update marks every playbook of the package stale(app_updated) and the fast path stops at once`() = runTest {
        storePlaybooks()
        // Atlas cache still shows the OLD version (sync is debounced) - must not matter.
        val oldEntry = AppAtlasEntry(packageName = PKG, label = "Example Video", versionCode = 150)
        assertThat(store.items.values.all { PlaybookCatalog.isRoutable(it, oldEntry, emptySet()) }).isTrue()

        val out = handler.handle(listOf(AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now)))

        assertThat(out.single().playbooksMarked).isEqualTo(2)
        for (sp in store.items.values) {
            assertThat(sp.status).isEqualTo(PlaybookStatus.STALE)
            assertThat(sp.staleReason).isEqualTo(StoredPlaybook.STALE_APP_UPDATED)
            assertThat(sp.flaggedVersion).isEqualTo(151L)
            assertThat(sp.isAppUpdatedStale).isTrue()
            // verifiedCount (branch Q) is kept; the status alone keeps the fast path off.
            assertThat(sp.verifiedCount).isAtLeast(1)
            assertThat(PlaybookCatalog.isRoutable(sp, oldEntry, emptySet())).isFalse()
            assertThat(PlaybookCatalog.isRoutable(sp, oldEntry.copy(versionCode = 151), emptySet())).isFalse()
            // playbook_run still replays it, strict (soft locators), until a verified owner run.
            assertThat(PlaybookPolicy.effectiveStatus(sp, 151)).isEqualTo(PlaybookStatus.UNVERIFIED)
        }
        // One relearn request per playbook, reason app_updated, for the new version.
        val relearn = repo.pullRelearn(markDelivered = false)
        assertThat(relearn.map { it.reason }.toSet()).containsExactly("app_updated")
        assertThat(relearn.map { it.versionCode }.toSet()).containsExactly(151L)
        assertThat(relearn).hasSize(2)

        // The same update seen again: nothing new.
        assertThat(handler.handle(listOf(AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now))).single().playbooksMarked).isEqualTo(0)
    }

    @Test
    fun `the atlas sync does not clear the mark, a verified owner run does`() = runTest {
        storePlaybooks()
        handler.handle(listOf(AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now)))
        val id = "$PKG/search"

        repo.onAppVersion(PKG, 151) // atlas sync of the same update (in range 100..200)
        assertThat(store.items[id]!!.isAppUpdatedStale).isTrue()

        repo.recordSuccess(id, verified = false) // remote or unconfirmed run
        assertThat(store.items[id]!!.isAppUpdatedStale).isTrue()

        repo.recordSuccess(id, verified = true)
        val healed = store.items[id]!!
        assertThat(healed.status).isEqualTo(PlaybookStatus.OK)
        assertThat(healed.staleReason).isNull()
        assertThat(PlaybookCatalog.isRoutable(healed, AppAtlasEntry(packageName = PKG, label = "Example Video", versionCode = 151), emptySet())).isTrue()
    }

    @Test
    fun `a sensitive package is only reported - no stale marking, no relearn`() = runTest {
        // Defensive: such a playbook can't be imported; even if one were stored it is not touched.
        val p = PlaybookFixtures.searchPlaybook().copy(id = "$BANK/search", packageName = BANK)
        store.items[p.id] = StoredPlaybook(p, source = PlaybookRepository.SOURCE_FILE)

        val out = handler.handle(listOf(AppChange(BANK, 10, 11, AppChange.Kind.UPDATED, now)))

        assertThat(out.single().sensitive).isTrue()
        assertThat(out.single().playbooksMarked).isEqualTo(0)
        assertThat(store.items[p.id]!!.status).isEqualTo(PlaybookStatus.OK)
        assertThat(store.items[p.id]!!.staleReason).isNull()
        assertThat(repo.pullRelearn(markDelivered = false)).isEmpty()
        // Reported as an update, flagged sensitive (Jarvis does not pull or crawl it).
        val (type, body) = sink.events.single()
        assertThat(type).isEqualTo("apps.changed")
        assertThat(body["pkg"]!!.jsonPrimitive.content).isEqualTo(BANK)
        assertThat(body["kind"]!!.jsonPrimitive.content).isEqualTo("updated")
        assertThat(body["sensitive"]!!.jsonPrimitive.boolean).isTrue()
    }

    @Test
    fun `apps changed payload shape - package and versions only`() = runTest {
        handler.handle(
            listOf(
                AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now),
                AppChange("com.example.maps", null, 3, AppChange.Kind.ADDED, now),
                AppChange("com.example.notes", 7, null, AppChange.Kind.REMOVED, now)
            )
        )
        assertThat(sink.events.map { it.first }.toSet()).containsExactly(AppChange.EVENT_TYPE)
        for ((_, body) in sink.events) {
            assertThat(body.keys).containsExactly("pkg", "oldVc", "newVc", "kind", "ts", "sensitive")
            assertThat(body["ts"]!!.jsonPrimitive.long).isEqualTo(now)
            assertThat(body["sensitive"]!!.jsonPrimitive.boolean).isFalse()
        }
        val updated = sink.events[0].second
        assertThat(updated["pkg"]!!.jsonPrimitive.content).isEqualTo(PKG)
        assertThat(updated["oldVc"]!!.jsonPrimitive.long).isEqualTo(150L)
        assertThat(updated["newVc"]!!.jsonPrimitive.long).isEqualTo(151L)
        assertThat(updated["kind"]!!.jsonPrimitive.content).isEqualTo("updated")
        assertThat(sink.events[1].second["oldVc"]).isEqualTo(JsonNull)
        assertThat(sink.events[1].second["kind"]!!.jsonPrimitive.content).isEqualTo("added")
        assertThat(sink.events[2].second["newVc"]).isEqualTo(JsonNull)
        assertThat(sink.events[2].second["kind"]!!.jsonPrimitive.content).isEqualTo("removed")
        assertThat(AppChange(PKG, 1, 2, AppChange.Kind.UPDATED, now).toEventBody(false).toString())
            .isEqualTo("""{"pkg":"$PKG","oldVc":1,"newVc":2,"kind":"updated","ts":$now,"sensitive":false}""")
    }

    @Test
    fun `no link sink - changes are still applied on the phone`() = runTest {
        storePlaybooks()
        val offline = AppUpdateHandler(repo, { false }, null)
        assertThat(offline.handle(listOf(AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now))).single().playbooksMarked).isEqualTo(2)
    }

    @Test
    fun `end to end - tracker change feeds the handler`() = runTest {
        storePlaybooks()
        tracker.checkChanged()
        apps.change(PKG, 151)
        val out = handler.handle(tracker.checkChanged())
        assertThat(out.single().playbooksMarked).isEqualTo(2)
        assertThat(sink.events).hasSize(1)
    }

    @Test
    fun `logs carry package name, kind and numbers only`() = runTest {
        val lines = mutableListOf<String>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { lines += message }
        }
        timber.log.Timber.plant(tree)
        try {
            storePlaybooks()
            handler.handle(listOf(AppChange(PKG, 150, 151, AppChange.Kind.UPDATED, now), AppChange(BANK, 1, 2, AppChange.Kind.UPDATED, now)))
        } finally {
            timber.log.Timber.uproot(tree)
        }
        val allowed = Regex("""AppUpdate: (added|updated|removed) [A-Za-z0-9_.]+ v-?[0-9]+ -> v-?[0-9]+, sensitive=(true|false), playbooks marked stale: [0-9]+""")
        assertThat(lines.filter { it.startsWith("AppUpdate:") }).hasSize(2)
        lines.filter { it.startsWith("AppUpdate:") }.forEach { assertThat(it).matches(allowed.pattern) }
        // No playbook text (descriptions, triggers, labels) in any line.
        val all = lines.joinToString(" | ")
        assertThat(all).doesNotContain("Search videos")
        assertThat(all).doesNotContain("Example Video")
    }

    private companion object {
        const val BANK = "com.example.bank"
    }
}
