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
 * - The very first run only records a baseline (no burst of "added"). An
 *   update seen by the very first broadcast before any baseline is part of
 *   that baseline (not reported) — only on the first start of R1.
 * - Committed after processing (gate R1 R-a): `known[pkg]` advances only when
 *   [checkChanged] / [onPackageEvent]'s `handle` returned true for the change,
 *   and the sequence only when every change was handled — a failed stale
 *   mark or a process death in between is retried by the next check.
 *   `handle` runs under the tracker's lock, so changes are processed in order.
 */
class AppUpdateTracker(
    private val apps: InstalledApps,
    private val state: AppUpdateState,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    /**
     * Start-up and the periodic job: everything changed since the persisted
     * sequence. Returns the changes that were handled (committed).
     */
    suspend fun checkChanged(handle: suspend (AppChange) -> Boolean = { true }): List<AppChange> = mutex.withLock {
        val boot = apps.bootCount()
        if (state.sequence < 0) {
            baselineLocked(boot)
            return@withLock emptyList()
        }
        val rebooted = boot != state.bootCount
        // The device rebooted: PackageManager's counter started again.
        val seq = if (rebooted) 0 else state.sequence
        var token = apps.changedSince(seq)
        if (token == null && seq > 0) {
            // Gate R-c: BOOT_COUNT unavailable (-1 both times) but the counter restarted below our sequence.
            apps.changedSince(0)?.takeIf { it.first < seq }?.let { token = it }
        }
        val t = token
        if (t == null) {
            if (rebooted || seq != state.sequence) commitSequence(seq, boot)
            return@withLock emptyList()
        }
        val (done, allOk) = processLocked(t.second, handle)
        // Gate R-b: boot count and sequence are written together, after the changes were committed.
        if (allOk) commitSequence(t.first, boot)
        done
    }

    /** A PACKAGE_ADDED / REPLACED / REMOVED broadcast for [packageName]. Returns the handled changes. */
    suspend fun onPackageEvent(packageName: String, handle: suspend (AppChange) -> Boolean = { true }): List<AppChange> =
        mutex.withLock {
            if (state.sequence < 0) {
                baselineLocked(apps.bootCount())
                return@withLock emptyList()
            }
            processLocked(listOf(packageName), handle).first
        }

    /** Forget everything (atlas turned off): the next check records a fresh baseline. */
    suspend fun reset() = mutex.withLock {
        state.saveKnown(emptyMap())
        state.sequence = -1
    }

    private fun commitSequence(seq: Int, boot: Int) {
        state.sequence = seq
        state.bootCount = boot
    }

    private fun baselineLocked(boot: Int) {
        state.saveKnown(apps.snapshot() - apps.ownPackage)
        commitSequence(apps.changedSince(0)?.first ?: 0, boot)
    }

    private suspend fun processLocked(
        packages: Collection<String>,
        handle: suspend (AppChange) -> Boolean
    ): Pair<List<AppChange>, Boolean> {
        val known = state.known().toMutableMap()
        val now = clock()
        val done = mutableListOf<AppChange>()
        var allOk = true
        for (pkg in packages.toSet()) {
            if (pkg == apps.ownPackage || !PACKAGE_RE.matches(pkg)) continue
            val old = known[pkg]
            val new = apps.versionOf(pkg)
            if (old == new) continue // dedup: this pkg + versionCode was already handled
            val kind = when {
                old == null -> AppChange.Kind.ADDED
                new == null -> AppChange.Kind.REMOVED
                else -> AppChange.Kind.UPDATED
            }
            val change = AppChange(pkg, old, new, kind, now)
            val ok = try {
                handle(change)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                allOk = false
                continue // not committed: the next check sees it again
            }
            if (new == null) known.remove(pkg) else known[pkg] = new
            state.saveKnown(known) // per change: a later crash keeps what was already handled
            done += change
        }
        return done to allOk
    }

    companion object {
        private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
