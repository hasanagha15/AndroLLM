package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.controller.SensitiveAppStore
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.appatlas.store.PlaybookStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Pure policy over stored playbooks: effective status for the installed app
 * version, what happens after a run, what an app update does.
 */
object PlaybookPolicy {

    /**
     * - STALE for the installed version → stale (do not replay; relearn queued);
     * - installed version outside the learned range → UNVERIFIED (replay strict);
     * - otherwise OK.
     */
    fun effectiveStatus(sp: StoredPlaybook, installedVersion: Long?): PlaybookStatus {
        val v = installedVersion ?: return if (sp.status == PlaybookStatus.STALE) PlaybookStatus.STALE else PlaybookStatus.OK
        if (sp.status == PlaybookStatus.STALE && sp.flaggedVersion == v) return PlaybookStatus.STALE
        return if (v < sp.playbook.versionMin || v > sp.playbook.versionMax) PlaybookStatus.UNVERIFIED else PlaybookStatus.OK
    }

    /** App updated to [versionCode]: out-of-range playbooks → UNVERIFIED (+ relearn), in-range → OK. */
    fun onAppVersion(items: List<StoredPlaybook>, versionCode: Long): List<StoredPlaybook> = items.map { sp ->
        val inRange = versionCode in sp.playbook.versionMin..sp.playbook.versionMax
        when {
            inRange && sp.status != PlaybookStatus.OK -> sp.copy(status = PlaybookStatus.OK, flaggedVersion = null)
            !inRange && !(sp.status == PlaybookStatus.STALE && sp.flaggedVersion == versionCode) ->
                sp.copy(status = PlaybookStatus.UNVERIFIED, flaggedVersion = versionCode)
            else -> sp
        }
    }
}

/**
 * Branch N: on-device playbook library (Room, app_atlas.db under
 * noBackupFilesDir). Imports validated bundles, answers lookups, records run
 * outcomes, queues relearn requests for Jarvis. Never stores slot values.
 */
@Singleton
class PlaybookRepository(
    private val store: PlaybookStore,
    /** The owner's protected packages (K: SensitiveAppStore), read live. */
    private val extrasSource: suspend () -> Set<String>
) {
    @Inject constructor(store: PlaybookStore, sensitiveApps: SensitiveAppStore) : this(store, { sensitiveApps.current() })

    private val mutex = Mutex()
    @Volatile private var cache: List<StoredPlaybook>? = null

    var clock: () -> Long = System::currentTimeMillis

    suspend fun extras(): Set<String> = runCatching { extrasSource() }.getOrDefault(emptySet())

    /**
     * Validates and stores a bundle. [labelOf] = installed app label (atlas)
     * for the sensitive heuristic; [source] "file" | "jarvis".
     */
    suspend fun importJson(
        raw: String,
        source: String,
        ownPackage: String?,
        labelOf: (String) -> String? = { null }
    ): ImportReport = withContext(Dispatchers.IO) {
        val report = PlaybookValidator.parseBundle(raw, extras(), ownPackage, labelOf)
        mutex.withLock {
            val now = clock()
            val existing = store.all().associateBy { it.playbook.id }
            val room = MAX_STORED - (existing.size - report.accepted.count { it.id in existing })
            val accepted = report.accepted.take(room.coerceAtLeast(0))
            val overflow = report.accepted.drop(accepted.size)
            store.upsert(accepted.map { p ->
                val prev = existing[p.id]
                StoredPlaybook(
                    playbook = p, status = PlaybookStatus.OK, flaggedVersion = null,
                    successCount = prev?.successCount ?: 0, failCount = prev?.failCount ?: 0,
                    lastRunAt = prev?.lastRunAt ?: 0L, importedAt = now, source = source
                )
            })
            cache = null
            val final = ImportReport(accepted, report.rejected + overflow.map { ImportReport.Rejection(it.id, "library full ($MAX_STORED)") })
            Timber.i("Playbooks: import from %s — %s", source, "accepted ${final.accepted.size}, rejected ${final.rejected.size}")
            final
        }
    }

    suspend fun all(): List<StoredPlaybook> = cache ?: withContext(Dispatchers.IO) { store.all().also { cache = it } }

    /** Playbooks of [pkg] that may run now (protected packages filtered live). */
    suspend fun forPackage(pkg: String, label: String? = null): List<StoredPlaybook> {
        val extras = extras()
        if (SensitiveClassifier.reason(pkg, label.orEmpty(), extras) != null) return emptyList()
        return all().filter { it.playbook.packageName == pkg }
    }

    suspend fun get(id: String): StoredPlaybook? = all().firstOrNull { it.playbook.id == id }

    /** Successful run: counters only. */
    suspend fun recordSuccess(id: String) = update(id) { it.copy(successCount = it.successCount + 1, lastRunAt = clock()) }

    /**
     * A mismatch (expected screen / target not found / wrong app): the
     * playbook becomes STALE for [versionCode] and a relearn request is queued.
     */
    suspend fun recordMismatch(id: String, versionCode: Long, failure: StepFailure) {
        val sp = get(id) ?: return
        update(id) { it.copy(status = PlaybookStatus.STALE, flaggedVersion = versionCode, failCount = it.failCount + 1, lastRunAt = clock()) }
        queueRelearn(sp.playbook, versionCode, failure.stepIndex, failure.kind.wire)
    }

    /** Non-mismatch failure (declined, blocked, action failed): counters only. */
    suspend fun recordFailure(id: String) = update(id) { it.copy(failCount = it.failCount + 1, lastRunAt = clock()) }

    /** Steps ran but the postcondition was not seen: relearn request, not stale. */
    suspend fun recordUnverified(id: String, versionCode: Long) {
        val sp = get(id) ?: return
        queueRelearn(sp.playbook, versionCode, sp.playbook.steps.size, "postcondition")
    }

    /** Branch N item 5: the atlas saw [pkg] update to [versionCode]. */
    suspend fun onAppVersion(pkg: String, versionCode: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = store.forPackage(pkg)
            if (items.isEmpty()) return@withLock
            val updated = PlaybookPolicy.onAppVersion(items, versionCode)
            val changed = updated.filterIndexed { i, sp -> sp != items[i] }
            store.upsert(changed)
            cache = null
            // Queued once per (playbook, version): only for entries that just became unverified.
            changed.filter { it.status == PlaybookStatus.UNVERIFIED && it.flaggedVersion == versionCode }.forEach {
                addRelearnLocked(it.playbook, versionCode, -1, "version_update")
            }
        }
    }

    /** Pending relearn requests for Jarvis; marked delivered once pulled. */
    suspend fun pullRelearn(limit: Int = 50, markDelivered: Boolean = true): List<RelearnRequest> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = store.pendingRelearn(limit.coerceIn(1, 200))
            if (markDelivered && list.isNotEmpty()) store.markDelivered(list)
            store.pruneDelivered(clock() - 30L * 24 * 3600 * 1000)
            list
        }
    }

    private suspend fun queueRelearn(p: Playbook, versionCode: Long, step: Int, reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock { addRelearnLocked(p, versionCode, step, reason) }
    }

    private suspend fun addRelearnLocked(p: Playbook, versionCode: Long, step: Int, reason: String) {
        // Ids and machine reasons only — never slot values or screen text.
        store.addRelearn(RelearnRequest(p.packageName, versionCode, p.id, step, reason, clock()))
    }

    private suspend fun update(id: String, block: (StoredPlaybook) -> StoredPlaybook) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sp = store.get(id) ?: return@withLock
            store.upsert(listOf(block(sp)))
            cache = null
        }
    }

    companion object {
        const val MAX_STORED = 2_000
    }
}
