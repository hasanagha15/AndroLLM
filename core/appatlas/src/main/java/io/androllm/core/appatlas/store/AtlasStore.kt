package io.androllm.core.appatlas.store

import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath

/** Version facts used for change detection. */
data class StoredVersion(val versionCode: Long, val lastUpdateTime: Long)

/**
 * Persistence of the atlas (Room on device, in-memory in tests). On-device
 * only: the Room file lives under noBackupFilesDir, so it is never part of a
 * cloud backup or device transfer.
 */
interface AtlasStore {
    suspend fun all(): List<AppAtlasEntry>
    suspend fun get(packageName: String): AppAtlasEntry?
    suspend fun upsert(entry: AppAtlasEntry)
    suspend fun remove(packageNames: Collection<String>)
    suspend fun versions(): Map<String, StoredVersion>

    suspend fun paths(packageName: String): List<LearnedPath>
    suspend fun allPaths(): List<LearnedPath>
    suspend fun upsertPath(path: LearnedPath)
    suspend fun replacePaths(packageName: String, paths: List<LearnedPath>)
    suspend fun clearPaths()
}
