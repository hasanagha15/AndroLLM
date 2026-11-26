package io.androllm.core.appatlas.timing

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import io.androllm.core.appatlas.playbook.TransitionKind

/**
 * Branch T: learned transition timing. Content-free: package, transition key
 * ("<playbook id>#<step>"), kind, UI type and numbers — never screen text.
 *
 * Kept in its own small database (transition_timing.db, noBackupFilesDir)
 * instead of app_atlas.db: no schema bump of the atlas, so it cannot collide
 * with other branches' atlas migrations (R1 adds playbook state concurrently).
 */
@Entity(tableName = "transition_timing", primaryKeys = ["packageName", "transitionKey"])
data class TimingRow(
    val packageName: String,
    val transitionKey: String,
    val kind: String,
    val uiType: String,
    val n: Int,
    val p50Ms: Double,
    val devMs: Double,
    val fails: Int,
    val updatedAt: Long
)

@Dao
abstract class TimingDao {
    @Query("SELECT * FROM transition_timing WHERE packageName = :pkg")
    abstract suspend fun forPackage(pkg: String): List<TimingRow>

    @Query("SELECT * FROM transition_timing ORDER BY updatedAt DESC LIMIT :limit")
    abstract suspend fun recent(limit: Int): List<TimingRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(rows: List<TimingRow>)

    @Query("DELETE FROM transition_timing WHERE packageName IN (:pkgs)")
    abstract suspend fun deleteFor(pkgs: List<String>)
}

@Database(entities = [TimingRow::class], version = 1, exportSchema = true)
abstract class TimingDatabase : RoomDatabase() {
    abstract fun dao(): TimingDao

    companion object {
        /** Opened under noBackupFilesDir (absolute path) — excluded from backup and transfer. */
        const val FILE_NAME = "transition_timing.db"
    }
}

class RoomTimingStore(private val dao: TimingDao) : TimingStore {
    override suspend fun forPackage(packageName: String): List<TimingRecord> = dao.forPackage(packageName).mapNotNull(::decode)
    override suspend fun upsert(records: List<TimingRecord>) = dao.upsert(records.map(::encode))
    override suspend fun all(limit: Int): List<TimingRecord> = dao.recent(limit).mapNotNull(::decode)

    companion object {
        fun decode(r: TimingRow): TimingRecord? {
            val kind = TransitionKind.of(r.kind) ?: return null
            return TimingRecord(r.packageName, r.transitionKey, kind, AppUiType.of(r.uiType), TimingStats(r.n, r.p50Ms, r.devMs, r.fails, r.updatedAt))
        }

        fun encode(t: TimingRecord) = TimingRow(
            packageName = t.packageName,
            transitionKey = t.transitionKey,
            kind = t.kind.wire,
            uiType = t.uiType.wire,
            n = t.stats.n,
            p50Ms = t.stats.p50Ms,
            devMs = t.stats.devMs,
            fails = t.stats.fails,
            updatedAt = t.stats.updatedAt
        )
    }
}
