package io.androllm.core.appatlas.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.model.PathStep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(tableName = "atlas_apps", primaryKeys = ["packageName"])
data class AtlasAppRow(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val sensitive: Boolean,
    /** JSON of [AppAtlasEntry]. */
    val payload: String,
    val indexedAt: Long
)

@Entity(tableName = "learned_paths", primaryKeys = ["packageName", "taskKey"])
data class LearnedPathRow(
    val packageName: String,
    val taskKey: String,
    val versionCode: Long,
    /** JSON list of [PathStep]. */
    val steps: String,
    val successCount: Int,
    val verified: Boolean,
    val stale: Boolean,
    val updatedAt: Long,
    val confirmStep: String? = null
)

data class VersionRow(val packageName: String, val versionCode: Long, val lastUpdateTime: Long)

@Dao
abstract class AtlasDao {
    @Query("SELECT * FROM atlas_apps")
    abstract suspend fun apps(): List<AtlasAppRow>

    @Query("SELECT * FROM atlas_apps WHERE packageName = :pkg")
    abstract suspend fun app(pkg: String): AtlasAppRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertApp(row: AtlasAppRow)

    @Query("DELETE FROM atlas_apps WHERE packageName IN (:pkgs)")
    abstract suspend fun deleteApps(pkgs: List<String>)

    @Query("DELETE FROM learned_paths WHERE packageName IN (:pkgs)")
    abstract suspend fun deletePathsFor(pkgs: List<String>)

    @Query("SELECT packageName, versionCode, lastUpdateTime FROM atlas_apps")
    abstract suspend fun versions(): List<VersionRow>

    @Query("SELECT * FROM learned_paths WHERE packageName = :pkg")
    abstract suspend fun paths(pkg: String): List<LearnedPathRow>

    @Query("SELECT * FROM learned_paths")
    abstract suspend fun allPaths(): List<LearnedPathRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPath(row: LearnedPathRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPaths(rows: List<LearnedPathRow>)

    @Query("DELETE FROM learned_paths")
    abstract suspend fun clearPaths()

    @Transaction
    open suspend fun replacePaths(pkg: String, rows: List<LearnedPathRow>) {
        deletePathsFor(listOf(pkg))
        upsertPaths(rows)
    }
}

// v3 (branch N): + playbooks, playbook_relearn (AtlasMigrations.MIGRATION_2_3). Schema exported to core/appatlas/schemas.
@Database(entities = [AtlasAppRow::class, LearnedPathRow::class, PlaybookRow::class, RelearnRow::class], version = 3, exportSchema = true)
abstract class AtlasDatabase : RoomDatabase() {
    abstract fun dao(): AtlasDao
    abstract fun playbookDao(): PlaybookDao

    companion object {
        /** Opened under noBackupFilesDir (absolute path) — excluded from backup and transfer. */
        const val FILE_NAME = "app_atlas.db"
    }
}

class RoomAtlasStore(private val dao: AtlasDao) : AtlasStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val stepsSerializer = ListSerializer(PathStep.serializer())

    override suspend fun all(): List<AppAtlasEntry> = dao.apps().mapNotNull(::decode)

    override suspend fun get(packageName: String): AppAtlasEntry? = dao.app(packageName)?.let(::decode)

    override suspend fun upsert(entry: AppAtlasEntry) {
        dao.upsertApp(
            AtlasAppRow(
                packageName = entry.packageName,
                label = entry.label,
                versionCode = entry.versionCode,
                lastUpdateTime = entry.lastUpdateTime,
                sensitive = entry.sensitive,
                payload = json.encodeToString(AppAtlasEntry.serializer(), entry),
                indexedAt = entry.indexedAt
            )
        )
    }

    override suspend fun remove(packageNames: Collection<String>) {
        if (packageNames.isEmpty()) return
        packageNames.chunked(400).forEach {
            dao.deleteApps(it)
            dao.deletePathsFor(it)
        }
    }

    override suspend fun versions(): Map<String, StoredVersion> =
        dao.versions().associate { it.packageName to StoredVersion(it.versionCode, it.lastUpdateTime) }

    override suspend fun paths(packageName: String): List<LearnedPath> = dao.paths(packageName).mapNotNull(::decodePath)

    override suspend fun allPaths(): List<LearnedPath> = dao.allPaths().mapNotNull(::decodePath)

    override suspend fun upsertPath(path: LearnedPath) = dao.upsertPath(encodePath(path))

    override suspend fun replacePaths(packageName: String, paths: List<LearnedPath>) =
        dao.replacePaths(packageName, paths.map(::encodePath))

    override suspend fun clearPaths() = dao.clearPaths()

    private fun decode(row: AtlasAppRow): AppAtlasEntry? =
        runCatching { json.decodeFromString(AppAtlasEntry.serializer(), row.payload) }.getOrNull()

    private fun decodePath(row: LearnedPathRow): LearnedPath? = runCatching {
        LearnedPath(
            packageName = row.packageName,
            taskKey = row.taskKey,
            versionCode = row.versionCode,
            steps = json.decodeFromString(stepsSerializer, row.steps),
            successCount = row.successCount,
            verified = row.verified,
            stale = row.stale,
            updatedAt = row.updatedAt,
            confirmStep = row.confirmStep
        )
    }.getOrNull()

    private fun encodePath(p: LearnedPath) = LearnedPathRow(
        packageName = p.packageName,
        taskKey = p.taskKey,
        versionCode = p.versionCode,
        steps = json.encodeToString(stepsSerializer, p.steps),
        successCount = p.successCount,
        verified = p.verified,
        stale = p.stale,
        updatedAt = p.updatedAt,
        confirmStep = p.confirmStep
    )
}
