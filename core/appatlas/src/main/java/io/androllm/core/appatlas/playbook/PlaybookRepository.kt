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
        // Branch R1: stale after an app update → never OK (the fast path stops at once, whatever
        // version the atlas cache still shows); playbook_run replays it strict until a verified owner run.
        if (sp.isAppUpdatedStale) return PlaybookStatus.UNVERIFIED
        val v = installedVersion ?: return if (sp.status == PlaybookStatus.STALE) PlaybookStatus.STALE else PlaybookStatus.OK
        if (sp.status == PlaybookStatus.STALE && sp.flaggedVersion == v) return PlaybookStatus.STALE
        return if (v < sp.playbook.versionMin || v > sp.playbook.versionMax) PlaybookStatus.UNVERIFIED else PlaybookStatus.OK
    }

    /**
     * Branch Q (N review rec 1): may the zero-LLM fast path route the owner's
     * command to [sp] by its triggers? Only a playbook the owner imported
     * from a local file, or one that already succeeded on this device with
     * its postcondition seen. A Jarvis-synced playbook can't take over an
     * owner's command until it has been verified here (the model can still
     * run it through playbook_run, which is how it gets verified).
     */
    fun fastPathTrusted(sp: StoredPlaybook): Boolean =
        sp.source == PlaybookRepository.SOURCE_FILE || sp.verifiedCount > 0

    /** App updated to [versionCode]: out-of-range playbooks → UNVERIFIED (+ relearn), in-range → OK. */
    fun onAppVersion(items: List<StoredPlaybook>, versionCode: Long): List<StoredPlaybook> = items.map { sp ->
        val inRange = versionCode in sp.playbook.versionMin..sp.playbook.versionMax
        when {
            // Branch R1: the atlas sync never clears an app-update stale mark (only a verified owner run does).
            sp.isAppUpdatedStale -> sp
            inRange && sp.status != PlaybookStatus.OK -> sp.copy(status = PlaybookStatus.OK, flaggedVersion = null)
            !inRange && !(sp.status == PlaybookStatus.STALE && sp.flaggedVersion == versionCode) ->
                sp.copy(status = PlaybookStatus.UNVERIFIED, flaggedVersion = versionCode)
            else -> sp
        }
    }

    /**
     * Branch R1: the app was updated (AppUpdateWatcher). Every playbook of the
     * package becomes STALE(app_updated) for [versionCode]: the fast path stops
     * at once. Already marked for this version → unchanged.
     */
    fun onAppUpdated(items: List<StoredPlaybook>, versionCode: Long): List<StoredPlaybook> = items.map { sp ->
        if (sp.isAppUpdatedStale && sp.flaggedVersion == versionCode) sp
        else sp.copy(status = PlaybookStatus.STALE, flaggedVersion = versionCode, staleReason = StoredPlaybook.STALE_APP_UPDATED)
    }

    /**
     * Branch R1 (design §2.3): a verified run of the OWNER (postcondition seen,
     * not a remote turn) heals an app-update stale mark — back to OK.
     */
    fun afterVerifiedRun(sp: StoredPlaybook): StoredPlaybook =
        if (sp.isAppUpdatedStale) sp.copy(status = PlaybookStatus.OK, flaggedVersion = null, staleReason = null) else sp
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
        appFacts: (String) -> AppFacts? = { null }
    ): ImportReport = withContext(Dispatchers.IO) {
        val report = PlaybookValidator.parseBundle(raw, extras(), ownPackage, appFacts)
        mutex.withLock {
            val now = clock()
            val existing = store.all().associateBy { it.playbook.id }
            val kept = mutableListOf<ImportReport.Rejection>()
            // Gate N: a remote (Jarvis) bundle never silently replaces a playbook the owner imported from a
            // file — only a NEWER (learnedAt) one that carries its own verifier (postcondition).
            val candidates = report.accepted.filter { p ->
                val prev = existing[p.id]
                val loses = prev != null && prev.source == SOURCE_FILE && source != SOURCE_FILE &&
                    !(p.learnedAt > prev.playbook.learnedAt && p.postcondition != null)
                if (loses) kept += ImportReport.Rejection(p.id, "kept the file-imported version (remote is not newer + verified)")
                !loses
            }
            val room = MAX_STORED - (existing.size - candidates.count { it.id in existing })
            val accepted = candidates.take(room.coerceAtLeast(0))
            val overflow = candidates.drop(accepted.size)
            store.upsert(accepted.map { p ->
                val prev = existing[p.id]
                // Gate N (P6): an unchanged playbook keeps its STALE / UNVERIFIED state.
                val same = prev != null && prev.playbook == p
                StoredPlaybook(
                    playbook = p,
                    status = if (same) prev!!.status else PlaybookStatus.OK,
                    flaggedVersion = if (same) prev!!.flaggedVersion else null,
                    // Branch R1: an unchanged re-import keeps the app-update stale mark too.
                    staleReason = if (same) prev!!.staleReason else null,
                    successCount = prev?.successCount ?: 0, failCount = prev?.failCount ?: 0,
                    lastRunAt = prev?.lastRunAt ?: 0L, importedAt = now,
                    source = if (same) prev!!.source else source,
                    // Branch Q: changed steps are unverified again (no inherited fast-path trust).
                    verifiedCount = if (same) prev!!.verifiedCount else 0
                )
            })
            cache = null
            val final = ImportReport(
                accepted,
                report.rejected + kept + overflow.map { ImportReport.Rejection(it.id, "library full ($MAX_STORED)") }
            )
            Timber.i("Playbooks: import from %s — %s", source, "accepted ${final.accepted.size}, rejected ${final.rejected.size}")
            final
        }
    }

    /** Reads under the mutex so a concurrent write can never leave a stale cache behind (review N10). */
    suspend fun all(): List<StoredPlaybook> = cache ?: withContext(Dispatchers.IO) {
        mutex.withLock { cache ?: store.all().also { cache = it } }
    }

    /**
     * Playbooks of [pkg] that may run now (protected packages filtered live).
     * [atlasSensitive] = the atlas flag (gate N-B3: payment-scheme apps).
     */
    suspend fun forPackage(pkg: String, label: String? = null, atlasSensitive: Boolean = false): List<StoredPlaybook> {
        if (atlasSensitive) return emptyList()
        val extras = extras()
        if (SensitiveClassifier.reason(pkg, label.orEmpty(), extras) != null) return emptyList()
        return all().filter { it.playbook.packageName == pkg }
    }

    suspend fun get(id: String): StoredPlaybook? = all().firstOrNull { it.playbook.id == id }

    /** Successful run: counters only. */
    suspend fun recordSuccess(id: String, verified: Boolean = false) = update(id) {
        val counted = it.copy(
            successCount = it.successCount + 1,
            verifiedCount = it.verifiedCount + if (verified) 1 else 0,
            lastRunAt = clock()
        )
        // Branch R1: self-heal after an app update (owner-verified runs only — the caller decides).
        if (verified) PlaybookPolicy.afterVerifiedRun(counted) else counted
    }

    /**
     * A mismatch (expected screen / target not found / wrong app): the
     * playbook becomes STALE for [versionCode] and a relearn request is queued.
     */
    suspend fun recordMismatch(id: String, versionCode: Long, failure: StepFailure) {
        val sp = get(id) ?: return
        update(id) {
            it.copy(
                status = PlaybookStatus.STALE, flaggedVersion = versionCode, staleReason = null,
                failCount = it.failCount + 1, lastRunAt = clock()
            )
        }
        // A mismatch is new information: it replaces a pending / delivered record of the same version.
        queueRelearn(sp.playbook, versionCode, failure.stepIndex, failure.kind.wire, replace = true)
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

    /**
     * Branch R1: AppUpdateWatcher saw [pkg] change to [versionCode]. All its
     * playbooks become STALE(app_updated) — the fast path stops matching them
     * immediately — and one relearn request `app_updated` per playbook is
     * queued (once per version). Returns how many playbooks were newly marked.
     * The caller never calls this for protected packages.
     */
    suspend fun onAppUpdated(pkg: String, versionCode: Long): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = store.forPackage(pkg)
            if (items.isEmpty()) return@withLock 0
            val updated = PlaybookPolicy.onAppUpdated(items, versionCode)
            val changed = updated.filterIndexed { i, sp -> sp != items[i] }
            store.upsert(changed)
            cache = null
            changed.forEach { addRelearnLocked(it.playbook, versionCode, -1, StoredPlaybook.STALE_APP_UPDATED) }
            changed.size
        }
    }

    /** Pending relearn requests for Jarvis; marked delivered once pulled. */
    suspend fun pullRelearn(limit: Int = DEFAULT_RELEARN_LIMIT, markDelivered: Boolean = true): List<RelearnRequest> =
        pullRelearnPage(limit, Int.MAX_VALUE, markDelivered).first

    /**
     * Review N1: a page of pending requests whose JSON fits [maxChars]; only
     * the records that fit are marked delivered. Returns (records, more pending).
     */
    suspend fun pullRelearnPage(limit: Int, maxChars: Int, markDelivered: Boolean = true): Pair<List<RelearnRequest>, Boolean> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val pending = store.pendingRelearn(limit.coerceIn(1, 200) + 1)
                val out = mutableListOf<RelearnRequest>()
                var size = 2
                for (r in pending.take(limit.coerceIn(1, 200))) {
                    val len = relearnJson.encodeToString(RelearnRequest.serializer(), r).length + 1
                    if (size + len > maxChars) break
                    size += len
                    out += r
                }
                if (markDelivered && out.isNotEmpty()) store.markDelivered(out)
                store.pruneDelivered(clock() - 30L * 24 * 3600 * 1000)
                out to (pending.size > out.size)
            }
        }

    /** A relearn request outside a run outcome (remote turn without fallback). Added once per (playbook, version). */
    suspend fun requestRelearn(p: Playbook, versionCode: Long, step: Int, reason: String) =
        queueRelearn(p, versionCode, step, reason, replace = false)

    private suspend fun queueRelearn(p: Playbook, versionCode: Long, step: Int, reason: String, replace: Boolean = false) =
        withContext(Dispatchers.IO) { mutex.withLock { addRelearnLocked(p, versionCode, step, reason, replace) } }

    /**
     * Ids and machine reasons only — never slot values or screen text.
     * Review N8: without [replace] an existing record (pending or delivered)
     * for the same (playbook, version) is kept — queued once.
     */
    private suspend fun addRelearnLocked(p: Playbook, versionCode: Long, step: Int, reason: String, replace: Boolean = false) {
        if (!replace && store.relearn(p.id, versionCode) != null) return
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
        const val DEFAULT_RELEARN_LIMIT = 30
        const val SOURCE_FILE = "file"
        const val SOURCE_JARVIS = "jarvis"
        private val relearnJson = kotlinx.serialization.json.Json { encodeDefaults = true }
    }
}
