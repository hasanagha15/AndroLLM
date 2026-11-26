package io.androllm.core.appatlas.update

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Branch R1: one app install / update / removal seen on this phone. Package name + versions only. */
data class AppChange(
    val packageName: String,
    /** versionCode before (null = newly installed). */
    val oldVersionCode: Long?,
    /** versionCode now (null = removed). */
    val newVersionCode: Long?,
    val kind: Kind,
    val ts: Long
) {
    enum class Kind(val wire: String) { ADDED("added"), UPDATED("updated"), REMOVED("removed") }

    /**
     * Body of the `apps.changed` event (documentation/PLAYBOOKS_SCHEMA.md):
     * `{"pkg","oldVc","newVc","kind","ts","sensitive"}` — nothing else.
     */
    fun toEventBody(sensitive: Boolean): JsonObject = buildJsonObject {
        put("pkg", packageName)
        put("oldVc", oldVersionCode?.let { JsonPrimitive(it) } ?: JsonNull)
        put("newVc", newVersionCode?.let { JsonPrimitive(it) } ?: JsonNull)
        put("kind", kind.wire)
        put("ts", ts)
        put("sensitive", sensitive)
    }

    companion object {
        const val EVENT_TYPE = "apps.changed"
    }
}

/** What the tracker needs from PackageManager (fake in tests). Must never throw. */
interface InstalledApps {
    /** versionCode of [packageName] when installed and launchable, else null. */
    fun versionOf(packageName: String): Long?

    /** Every launchable app → versionCode (the baseline). */
    fun snapshot(): Map<String, Long>

    /** `PackageManager.getChangedPackages(sequence)`: (new sequence, packages) or null = nothing changed. */
    fun changedSince(sequence: Int): Pair<Int, List<String>>?

    /** `Settings.Global.BOOT_COUNT` (-1 unknown): the change sequence restarts at 0 after a reboot. */
    fun bootCount(): Int

    val ownPackage: String
}

/** Persisted tracker state (SharedPreferences on device, in memory in tests). */
interface AppUpdateState {
    /** Last `getChangedPackages` sequence number; -1 = no baseline yet. */
    var sequence: Int
    var bootCount: Int
    /** Last seen versionCode per package (the dedup memory: pkg + versionCode is reported once). */
    fun known(): Map<String, Long>
    fun saveKnown(known: Map<String, Long>)
}

/**
 * Branch R1: turns "something about these packages changed" (a PACKAGE_*
 * broadcast or the PackageManager change token) into [AppChange]s.
 *
 * - Dedup by package + versionCode: a change is reported once, however many
 *   broadcasts / token polls see it (REMOVED+ADDED+REPLACED of one update,
 *   the 15-minute poll after the receiver already saw it, a restart).
 * - The sequence number is persisted; after a reboot (boot count changed)
 *   it restarts at 0, as PackageManager's does.
 * - The very first run only records a baseline (no burst of "added").
 */
class AppUpdateTracker(
    private val apps: InstalledApps,
    private val state: AppUpdateState,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    /** Start-up and the periodic job: everything changed since the persisted sequence. */
    suspend fun checkChanged(): List<AppChange> = mutex.withLock {
        val boot = apps.bootCount()
        if (state.sequence < 0) {
            baselineLocked(boot)
            return@withLock emptyList()
        }
        var seq = state.sequence
        if (boot != state.bootCount) {
            seq = 0 // the device rebooted: PackageManager's counter started again
            state.bootCount = boot
        }
        val token = apps.changedSince(seq) ?: run {
            if (seq != state.sequence) state.sequence = seq
            return@withLock emptyList()
        }
        val changes = reconcileLocked(token.second)
        state.sequence = token.first // after the known map: a crash in between only re-checks (dedup)
        changes
    }

    /** A PACKAGE_ADDED / REPLACED / REMOVED broadcast for [packageName]. */
    suspend fun onPackageEvent(packageName: String): List<AppChange> = mutex.withLock {
        if (state.sequence < 0) {
            baselineLocked(apps.bootCount())
            return@withLock emptyList()
        }
        reconcileLocked(listOf(packageName))
    }

    /** Forget everything (atlas turned off): the next check records a fresh baseline. */
    suspend fun reset() = mutex.withLock {
        state.saveKnown(emptyMap())
        state.sequence = -1
    }

    private fun baselineLocked(boot: Int) {
        state.saveKnown(apps.snapshot() - apps.ownPackage)
        state.bootCount = boot
        state.sequence = apps.changedSince(0)?.first ?: 0
    }

    private fun reconcileLocked(packages: Collection<String>): List<AppChange> {
        val known = state.known().toMutableMap()
        val now = clock()
        val out = mutableListOf<AppChange>()
        for (pkg in packages.toSet()) {
            if (pkg == apps.ownPackage || !PACKAGE_RE.matches(pkg)) continue
            val old = known[pkg]
            val new = apps.versionOf(pkg)
            if (old == new) continue // dedup: this pkg + versionCode was already reported
            val kind = when {
                old == null -> AppChange.Kind.ADDED
                new == null -> AppChange.Kind.REMOVED
                else -> AppChange.Kind.UPDATED
            }
            if (new == null) known.remove(pkg) else known[pkg] = new
            out += AppChange(pkg, old, new, kind, now)
        }
        if (out.isNotEmpty()) state.saveKnown(known)
        return out
    }

    companion object {
        private val PACKAGE_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    }
}
