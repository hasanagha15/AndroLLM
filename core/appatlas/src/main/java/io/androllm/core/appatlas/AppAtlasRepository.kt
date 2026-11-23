package io.androllm.core.appatlas

import io.androllm.core.appatlas.index.AtlasIndexer
import io.androllm.core.appatlas.learn.LearnedPathPolicy
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.search.AtlasHit
import io.androllm.core.appatlas.search.AtlasSearch
import io.androllm.core.appatlas.settings.AppAtlasSettingsStore
import io.androllm.core.appatlas.source.PackageSource
import io.androllm.core.appatlas.store.AtlasStore
import io.androllm.core.appatlas.sync.AtlasSynchronizer
import io.androllm.core.appatlas.sync.SyncReport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

data class AtlasStatus(
    val appsIndexed: Int = 0,
    val sensitiveApps: Int = 0,
    val learnedPaths: Int = 0,
    val lastSyncAt: Long = 0L,
    val running: Boolean = false,
    val lastReport: String? = null
)

/** Single entry point of the atlas: sync, search, entries, learned paths, status. */
@Singleton
class AppAtlasRepository @Inject constructor(
    private val source: PackageSource,
    private val store: AtlasStore,
    private val settingsStore: AppAtlasSettingsStore
) {
    private val indexer = AtlasIndexer(source)
    private val synchronizer = AtlasSynchronizer(source, store, indexer)
    private val syncMutex = Mutex()

    @Volatile private var cache: List<AppAtlasEntry>? = null
    @Volatile private var pathCache: Map<String, List<LearnedPath>>? = null

    private val _status = MutableStateFlow(AtlasStatus())
    val status: StateFlow<AtlasStatus> = _status.asStateFlow()

    val isEnabled: Boolean get() = settingsStore.current().enabled

    enum class Mode { INCREMENTAL, SWEEP, FULL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget [sync] (broadcasts, Settings button, first tool use). */
    fun requestSync(mode: Mode, only: Set<String>? = null) {
        scope.launch { sync(mode, only) }
    }

    /**
     * Brings the atlas up to date. INCREMENTAL = change token + version
     * compare (start-up, package broadcasts), SWEEP = weekly version compare,
     * FULL = re-index everything (Settings button).
     */
    suspend fun sync(mode: Mode, only: Set<String>? = null): SyncReport? = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext null
        syncMutex.withLock {
            _status.value = _status.value.copy(running = true)
            try {
                val settings = settingsStore.current()
                // PackageManager change token: same-version reinstalls/updates too.
                val seq = settingsStore.changeSequence
                val token = source.changedSince(if (seq < 0) 0 else seq)
                val changed = token?.second.orEmpty().toSet()
                token?.let { settingsStore.changeSequence = it.first }
                // Gate B2: protected-app rules / the owner's list changed → re-index everything.
                val fingerprint = source.sensitivityFingerprint()
                val rulesChanged = fingerprint != settingsStore.sensitivityFingerprint
                val report = synchronizer.sync(
                    exclusions = settings.exclusions,
                    force = mode == Mode.FULL || rulesChanged,
                    only = (only.orEmpty() + if (seq >= 0) changed else emptySet()).ifEmpty { null }
                )
                settingsStore.sensitivityFingerprint = fingerprint
                val now = System.currentTimeMillis()
                settingsStore.lastSyncAt = now
                if (mode != Mode.INCREMENTAL) settingsStore.lastSweepAt = now
                reload()
                _status.value = _status.value.copy(lastReport = report.toString())
                Timber.i("AppAtlas: sync %s — %s", mode, report)
                report
            } catch (t: Throwable) {
                Timber.w(t, "AppAtlas: sync failed")
                _status.value = _status.value.copy(lastReport = "sync failed: ${t.javaClass.simpleName}")
                null
            } finally {
                _status.value = _status.value.copy(running = false)
            }
        }
    }

    suspend fun entries(): List<AppAtlasEntry> {
        cache?.let { return it }
        reload()
        return cache.orEmpty()
    }

    /** The entry of [pkg]; indexes it on the fly when the atlas has not seen it yet. */
    suspend fun entry(pkg: String): AppAtlasEntry? = withContext(Dispatchers.IO) {
        if (pkg in settingsStore.current().exclusions) return@withContext null
        entries().firstOrNull { it.packageName == pkg }
            ?: source.basics(pkg)?.takeIf { pkg != source.ownPackage }?.let { b ->
                indexer.index(b).also { store.upsert(it); reload() }
            }
    }

    /** Cached lookup for hot paths (UI step listener) — never touches the database. */
    fun cachedEntry(pkg: String): AppAtlasEntry? = cache?.firstOrNull { it.packageName == pkg }

    suspend fun search(query: String): List<AtlasHit> {
        val excluded = settingsStore.current().exclusions
        val entries = entries().filter { it.packageName !in excluded }
        return AtlasSearch.search(entries, query, paths())
    }

    suspend fun paths(): Map<String, List<LearnedPath>> {
        pathCache?.let { return it }
        reload()
        return pathCache.orEmpty()
    }

    /** Persists recorded paths (merged with what is stored). */
    suspend fun recordPaths(recorded: List<LearnedPath>) = withContext(Dispatchers.IO) {
        if (recorded.isEmpty() || !settingsStore.current().recordPaths) return@withContext
        for (p in recorded) {
            if (p.packageName in settingsStore.current().exclusions) continue
            val existing = store.paths(p.packageName).firstOrNull { it.taskKey == p.taskKey }
            store.upsertPath(LearnedPathPolicy.merge(existing, p))
        }
        reload()
    }

    suspend fun clearPaths() = withContext(Dispatchers.IO) {
        store.clearPaths()
        reload()
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        val all = store.all()
        val paths = store.allPaths()
        cache = all.sortedBy { it.label.lowercase() }
        pathCache = paths.groupBy { it.packageName }
        _status.value = _status.value.copy(
            appsIndexed = all.size,
            sensitiveApps = all.count { it.sensitive },
            learnedPaths = paths.size,
            lastSyncAt = settingsStore.lastSyncAt
        )
    }
}
