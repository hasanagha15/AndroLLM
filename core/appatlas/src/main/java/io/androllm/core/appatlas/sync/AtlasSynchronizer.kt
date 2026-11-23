package io.androllm.core.appatlas.sync

import io.androllm.core.appatlas.index.AtlasIndexer
import io.androllm.core.appatlas.learn.LearnedPathPolicy
import io.androllm.core.appatlas.source.PackageSource
import io.androllm.core.appatlas.store.AtlasStore
import kotlinx.coroutines.yield

data class SyncReport(
    val indexed: Int,
    val removed: Int,
    val unchanged: Int,
    /** Learned paths newly flagged stale because their app updated. */
    val stalePaths: Int
) {
    override fun toString(): String = "indexed $indexed, removed $removed, unchanged $unchanged, stale paths $stalePaths"
}

/**
 * Reconciles the stored atlas with what is installed (pure orchestration over
 * [PackageSource] + [AtlasStore], unit-tested with fakes):
 * - new app, or versionCode / lastUpdateTime changed → re-index;
 * - [only] (from PACKAGE_* broadcasts / the change token) → re-index even if
 *   the version looks the same; [force] → re-index everything;
 * - uninstalled or excluded → removed together with its learned paths;
 * - versionCode changed → learned paths of the old version become stale hints.
 */
class AtlasSynchronizer(
    private val source: PackageSource,
    private val store: AtlasStore,
    private val indexer: AtlasIndexer
) {

    suspend fun sync(exclusions: Set<String>, force: Boolean = false, only: Set<String>? = null): SyncReport {
        val current = source.launchablePackages()
            .filter { it.packageName != source.ownPackage && it.packageName !in exclusions }
        val stored = store.versions()
        val removed = stored.keys - current.map { it.packageName }.toSet()

        var indexed = 0
        var unchanged = 0
        var stale = 0
        for (b in current) {
            val prev = stored[b.packageName]
            val changed = prev == null || prev.versionCode != b.versionCode || prev.lastUpdateTime != b.lastUpdateTime
            if (!force && !changed && only?.contains(b.packageName) != true) {
                unchanged++
                continue
            }
            store.upsert(indexer.index(b))
            indexed++
            if (prev != null && prev.versionCode != b.versionCode) {
                val paths = store.paths(b.packageName)
                if (paths.isNotEmpty()) {
                    val updated = LearnedPathPolicy.onVersionChange(paths, b.versionCode)
                    stale += updated.count { it.stale } - paths.count { it.stale }
                    store.replacePaths(b.packageName, updated)
                }
            }
            yield()
        }
        store.remove(removed)
        return SyncReport(indexed, removed.size, unchanged, stale)
    }
}
