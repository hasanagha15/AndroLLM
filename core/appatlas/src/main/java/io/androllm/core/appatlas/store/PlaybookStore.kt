package io.androllm.core.appatlas.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.androllm.core.appatlas.playbook.Playbook
import io.androllm.core.appatlas.playbook.PlaybookStatus
import io.androllm.core.appatlas.playbook.RelearnRequest
import io.androllm.core.appatlas.playbook.StoredPlaybook
import kotlinx.serialization.json.Json

/** Branch N: one imported playbook (JSON payload) + its on-device state. No user data. */
@Entity(tableName = "playbooks", primaryKeys = ["id"], indices = [Index("packageName")])
data class PlaybookRow(
    val id: String,
    val packageName: String,
    val name: String,
    val versionMin: Long,
    val versionMax: Long,
    val risky: Boolean,
    /** JSON of [Playbook] (validated on import). */
    val payload: String,
    /** OK | UNVERIFIED | STALE. */
    val status: String,
    val flaggedVersion: Long?,
    val successCount: Int,
    val failCount: Int,
    val lastRunAt: Long,
    val importedAt: Long,
    val source: String,
    /** Branch Q: runs that succeeded with the postcondition seen on this device. */
    @ColumnInfo(defaultValue = "0")
    val verifiedCount: Int = 0,
    /** Branch R1: why the row is STALE (`app_updated`); null = replay mismatch / not stale. */
    val staleReason: String? = null
)

/** Branch N: a pending relearn request for Jarvis (one per playbook + app version). Ids only. */
@Entity(tableName = "playbook_relearn", primaryKeys = ["playbookId", "versionCode"])
data class RelearnRow(
    val playbookId: String,
    val versionCode: Long,
    val packageName: String,
    val failedStep: Int,
    val reason: String,
    val createdAt: Long,
    val delivered: Boolean
)

@Dao
abstract class PlaybookDao {
    @Query("SELECT * FROM playbooks")
    abstract suspend fun all(): List<PlaybookRow>

    @Query("SELECT * FROM playbooks WHERE packageName = :pkg")
    abstract suspend fun forPackage(pkg: String): List<PlaybookRow>

    @Query("SELECT * FROM playbooks WHERE id = :id")
    abstract suspend fun get(id: String): PlaybookRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(rows: List<PlaybookRow>)

    @Query("DELETE FROM playbooks WHERE id IN (:ids)")
    abstract suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM playbooks")
    abstract suspend fun count(): Int

    @Query("SELECT * FROM playbook_relearn WHERE delivered = 0 ORDER BY createdAt LIMIT :limit")
    abstract suspend fun pendingRelearn(limit: Int): List<RelearnRow>

    @Query("SELECT * FROM playbook_relearn WHERE playbookId = :id AND versionCode = :version")
    abstract suspend fun relearn(id: String, version: Long): RelearnRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRelearn(row: RelearnRow)

    @Query("UPDATE playbook_relearn SET delivered = 1 WHERE playbookId = :id AND versionCode = :version")
    abstract suspend fun markDelivered(id: String, version: Long)

    @Query("DELETE FROM playbook_relearn WHERE delivered = 1 AND createdAt < :before")
    abstract suspend fun pruneDelivered(before: Long)
}

/** Persistence of playbooks (Room on device, in-memory in tests). */
interface PlaybookStore {
    suspend fun all(): List<StoredPlaybook>
    suspend fun forPackage(packageName: String): List<StoredPlaybook>
    suspend fun get(id: String): StoredPlaybook?
    suspend fun upsert(items: List<StoredPlaybook>)
    suspend fun delete(ids: Collection<String>)
    suspend fun count(): Int

    suspend fun relearn(id: String, versionCode: Long): RelearnRequest?
    suspend fun addRelearn(request: RelearnRequest)
    suspend fun pendingRelearn(limit: Int): List<RelearnRequest>
    suspend fun markDelivered(requests: List<RelearnRequest>)
    suspend fun pruneDelivered(before: Long)
}

class RoomPlaybookStore(private val dao: PlaybookDao) : PlaybookStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override suspend fun all() = dao.all().mapNotNull(::decode)
    override suspend fun forPackage(packageName: String) = dao.forPackage(packageName).mapNotNull(::decode)
    override suspend fun get(id: String) = dao.get(id)?.let(::decode)
    override suspend fun upsert(items: List<StoredPlaybook>) { if (items.isNotEmpty()) dao.upsert(items.map(::encode)) }
    override suspend fun delete(ids: Collection<String>) { ids.chunked(400).forEach { dao.delete(it) } }
    override suspend fun count() = dao.count()

    override suspend fun relearn(id: String, versionCode: Long) = dao.relearn(id, versionCode)?.let(::toRequest)
    override suspend fun addRelearn(request: RelearnRequest) = dao.upsertRelearn(
        RelearnRow(request.playbookId, request.versionCode, request.packageName, request.failedStep, request.reason, request.createdAt, false)
    )
    override suspend fun pendingRelearn(limit: Int) = dao.pendingRelearn(limit).map(::toRequest)
    override suspend fun markDelivered(requests: List<RelearnRequest>) = requests.forEach { dao.markDelivered(it.playbookId, it.versionCode) }
    override suspend fun pruneDelivered(before: Long) = dao.pruneDelivered(before)

    private fun toRequest(r: RelearnRow) = RelearnRequest(r.packageName, r.versionCode, r.playbookId, r.failedStep, r.reason, r.createdAt)

    private fun decode(r: PlaybookRow): StoredPlaybook? = runCatching {
        StoredPlaybook(
            playbook = json.decodeFromString(Playbook.serializer(), r.payload),
            status = runCatching { PlaybookStatus.valueOf(r.status) }.getOrDefault(PlaybookStatus.OK),
            flaggedVersion = r.flaggedVersion,
            successCount = r.successCount,
            failCount = r.failCount,
            lastRunAt = r.lastRunAt,
            importedAt = r.importedAt,
            source = r.source,
            verifiedCount = r.verifiedCount,
            staleReason = r.staleReason
        )
    }.getOrNull()

    private fun encode(s: StoredPlaybook) = PlaybookRow(
        id = s.playbook.id,
        packageName = s.playbook.packageName,
        name = s.playbook.name,
        versionMin = s.playbook.versionMin,
        versionMax = s.playbook.versionMax,
        risky = s.playbook.isRisky,
        payload = json.encodeToString(Playbook.serializer(), s.playbook),
        status = s.status.name,
        flaggedVersion = s.flaggedVersion,
        successCount = s.successCount,
        failCount = s.failCount,
        lastRunAt = s.lastRunAt,
        importedAt = s.importedAt,
        source = s.source,
        verifiedCount = s.verifiedCount,
        staleReason = s.staleReason
    )
}

/** Room schema migrations of app_atlas.db. */
object AtlasMigrations {

    /**
     * v2 → v3 (branch N): adds the playbook tables; atlas apps and learned
     * paths are kept. Statements mirror Room's generated schema
     * (core/appatlas/schemas/…/3.json — verified by AtlasMigrationTest).
     */
    val SQL_2_3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `playbooks` (`id` TEXT NOT NULL, `packageName` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`versionMin` INTEGER NOT NULL, `versionMax` INTEGER NOT NULL, `risky` INTEGER NOT NULL, `payload` TEXT NOT NULL, " +
            "`status` TEXT NOT NULL, `flaggedVersion` INTEGER, `successCount` INTEGER NOT NULL, `failCount` INTEGER NOT NULL, " +
            "`lastRunAt` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_playbooks_packageName` ON `playbooks` (`packageName`)",
        "CREATE TABLE IF NOT EXISTS `playbook_relearn` (`playbookId` TEXT NOT NULL, `versionCode` INTEGER NOT NULL, " +
            "`packageName` TEXT NOT NULL, `failedStep` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`delivered` INTEGER NOT NULL, PRIMARY KEY(`playbookId`, `versionCode`))"
    )

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_2_3.forEach(db::execSQL)
        }
    }

    /**
     * v3 → v4 (branch Q): playbooks.verifiedCount — successful runs with the
     * postcondition seen on this device. Existing rows start unverified (0);
     * a Jarvis-synced playbook needs one verified run before the fast path
     * may route to it (file imports are trusted as before).
     */
    val SQL_3_4: List<String> = listOf(
        "ALTER TABLE `playbooks` ADD COLUMN `verifiedCount` INTEGER NOT NULL DEFAULT 0"
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_3_4.forEach(db::execSQL)
        }
    }

    /**
     * v4 → v5 (branch R1): playbooks.staleReason — `app_updated` when the
     * AppUpdateWatcher saw the app update (fast path off until a verified
     * owner run). Existing rows keep their state (null = branch N meaning).
     */
    val SQL_4_5: List<String> = listOf(
        "ALTER TABLE `playbooks` ADD COLUMN `staleReason` TEXT"
    )

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_4_5.forEach(db::execSQL)
        }
    }
}
