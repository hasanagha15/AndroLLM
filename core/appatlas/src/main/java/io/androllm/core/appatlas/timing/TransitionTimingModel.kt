package io.androllm.core.appatlas.timing

import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.appatlas.playbook.Transition
import io.androllm.core.appatlas.playbook.TransitionKind
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Branch T: how an app builds its UI. Decides the defaults for unknown
 * transitions and the "screen stable" window: Compose / Flutter trees and
 * WebViews reach the accessibility tree later than classic Views.
 */
enum class AppUiType(val wire: String, val defaultScale: Double, val debounceMs: Long, val stableMs: Long) {
    VIEWS("views", 1.0, 60L, 80L),
    COMPOSE("compose", 1.15, 90L, 140L),
    WEB("web", 1.5, 140L, 250L),
    UNKNOWN("unknown", 1.1, 80L, 120L);

    companion object {
        fun of(wire: String?): AppUiType = entries.firstOrNull { it.wire == wire } ?: UNKNOWN

        /**
         * From the class names / view ids of one snapshot (no text is read):
         * a WebView node → WEB; ≥ 85 % of ≥ 6 nodes without a resource id →
         * COMPOSE (Compose and Flutter expose no view ids); else VIEWS.
         */
        fun detect(s: NodeSnapshot?): AppUiType {
            val nodes = s?.nodes ?: return UNKNOWN
            if (nodes.size < 6) return UNKNOWN
            if (nodes.any { it.className?.contains("WebView") == true }) return WEB
            val noId = nodes.count { it.viewId.isNullOrEmpty() }
            return if (noId >= nodes.size * 0.85) COMPOSE else VIEWS
        }
    }
}

/**
 * Load-normalized timing of one transition (time ÷ k at the moment of the
 * run, i.e. "as on an idle, cool phone"). EWMA of the median and of the
 * absolute deviation; p95 ≈ p50 + [Z] × deviation (Laplace-like tails seen in
 * the branch T measurements). Numbers only.
 */
data class TimingStats(
    val n: Int = 0,
    val p50Ms: Double = 0.0,
    val devMs: Double = 0.0,
    val fails: Int = 0,
    val updatedAt: Long = 0L
) {
    val p95Ms: Double get() = max(p50Ms + Z * devMs, p50Ms * P95_MIN_RATIO)

    /** One successful observation [x] (normalized ms). Outliers are clamped to [OUTLIER_X] × p95. */
    fun update(x: Double, now: Long): TimingStats {
        if (x.isNaN() || x < 0) return this
        if (n == 0) return copy(n = 1, p50Ms = x, devMs = x * FIRST_DEV_RATIO, updatedAt = now)
        val xc = x.coerceAtMost(p95Ms * OUTLIER_X)
        val a = max(1.0 / (n + 1), ALPHA)
        val p50 = p50Ms + a * (xc - p50Ms)
        val dev = devMs + a * (abs(xc - p50Ms) - devMs)
        return copy(n = (n + 1).coerceAtMost(N_CAP), p50Ms = p50, devMs = dev, updatedAt = now)
    }

    /** A timeout (even after the retry): the real time is longer than what we waited — widen the spread. */
    fun failed(waitedNormMs: Double, now: Long): TimingStats {
        val f = copy(fails = (fails + 1).coerceAtMost(N_CAP), updatedAt = now)
        if (n == 0 || waitedNormMs.isNaN()) return f
        val dev = max(devMs, (waitedNormMs - p50Ms) / Z)
        return f.copy(devMs = devMs + ALPHA * (dev - devMs))
    }

    companion object {
        const val ALPHA = 0.2
        const val Z = 2.5
        const val P95_MIN_RATIO = 1.25
        const val FIRST_DEV_RATIO = 0.3
        const val OUTLIER_X = 4.0
        const val N_CAP = 10_000
        /** Below this many runs the learned value is blended with the default. */
        const val TRUSTED_N = 3
    }
}

/** Defaults for transitions with no history (branch T measurements, idle phone, k = 1). */
object TimingDefaults {
    data class Default(val p50Ms: Double, val p95Ms: Double)

    /** Calibrated on the owner's phone — see T-result.md (k = 1 values). */
    fun of(kind: TransitionKind, type: AppUiType): Default {
        val base = when (kind) {
            // launcher intent: hot ≈ 50–100 ms, warm stable ≈ 600 ms, cold stable 0.4–2.4 s
            TransitionKind.LAUNCH -> Default(600.0, 2_000.0)
            TransitionKind.DEEPLINK -> Default(600.0, 1_500.0)
            // tabs / list → detail, input → last frame of the burst: p50 426, p95 1 101 (n = 38)
            TransitionKind.IN_APP -> Default(400.0, 1_100.0)
            // popup menu: 330–390 ms
            TransitionKind.DIALOG -> Default(300.0, 600.0)
        }
        return Default(base.p50Ms * type.defaultScale, base.p95Ms * type.defaultScale)
    }
}

/** What the model expects for one transition, normalized to k = 1. */
data class TimingEstimate(val p50Ms: Double, val p95Ms: Double, val learnedRuns: Int)

/** Persisted timing row (content-free: package, "<playbook id>#<step>", kind, numbers). */
data class TimingRecord(
    val packageName: String,
    val transitionKey: String,
    val kind: TransitionKind,
    val uiType: AppUiType,
    val stats: TimingStats
)

/** Storage of [TimingRecord]s (Room in production, memory in tests). */
interface TimingStore {
    suspend fun forPackage(packageName: String): List<TimingRecord>
    suspend fun upsert(records: List<TimingRecord>)
    suspend fun all(limit: Int): List<TimingRecord>
}

/**
 * Branch T: per (package, transition) EWMA of p50 / p95 learned from real
 * playbook runs, plus defaults by app UI type for unknown transitions.
 * Values are load-normalized: the caller divides an observation by the k it
 * ran under and multiplies the estimate by the current k.
 */
class TransitionTimingModel(
    private val store: TimingStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, HashMap<String, TimingRecord>>()
    private val dirty = HashSet<Pair<String, String>>()

    private suspend fun pkg(packageName: String): HashMap<String, TimingRecord> =
        cache.getOrPut(packageName) {
            runCatching { store.forPackage(packageName) }.getOrDefault(emptyList())
                .associateByTo(HashMap()) { it.transitionKey }
        }

    /** The UI type last seen for [packageName] (from its stored rows), UNKNOWN if none. */
    suspend fun uiTypeHint(packageName: String): AppUiType = mutex.withLock {
        pkg(packageName).values.filter { it.uiType != AppUiType.UNKNOWN }.maxByOrNull { it.stats.updatedAt }?.uiType ?: AppUiType.UNKNOWN
    }

    suspend fun estimate(t: Transition, type: AppUiType): TimingEstimate = mutex.withLock {
        val d = TimingDefaults.of(t.kind, type)
        val s = pkg(t.packageName)[t.key]?.stats
        if (s == null || s.n == 0) return@withLock TimingEstimate(d.p50Ms, d.p95Ms, 0)
        if (s.n >= TimingStats.TRUSTED_N) return@withLock TimingEstimate(s.p50Ms, s.p95Ms, s.n)
        // Few runs: blend towards the default so one lucky run cannot shrink the timeout.
        val w = s.n.toDouble() / TimingStats.TRUSTED_N
        TimingEstimate(
            p50Ms = w * s.p50Ms + (1 - w) * d.p50Ms,
            p95Ms = max(w * s.p95Ms + (1 - w) * d.p95Ms, s.p95Ms),
            learnedRuns = s.n
        )
    }

    suspend fun record(t: Transition, type: AppUiType, normalizedMs: Double, ok: Boolean) = mutex.withLock {
        val m = pkg(t.packageName)
        val prev = m[t.key] ?: TimingRecord(t.packageName, t.key, t.kind, type, TimingStats())
        val now = clock()
        val stats = if (ok) prev.stats.update(normalizedMs, now) else prev.stats.failed(normalizedMs, now)
        m[t.key] = prev.copy(kind = t.kind, uiType = if (type != AppUiType.UNKNOWN) type else prev.uiType, stats = stats)
        dirty += t.packageName to t.key
    }

    /** Writes changed rows. */
    suspend fun flush() {
        val rows = mutex.withLock {
            val r = dirty.mapNotNull { (p, k) -> cache[p]?.get(k) }
            dirty.clear()
            r
        }
        if (rows.isNotEmpty()) runCatching { store.upsert(rows) }
    }

    suspend fun snapshot(limit: Int): List<TimingRecord> {
        flush()
        return runCatching { store.all(limit) }.getOrDefault(emptyList())
    }
}

/** In-memory [TimingStore] (tests). */
class MemoryTimingStore : TimingStore {
    val rows = LinkedHashMap<Pair<String, String>, TimingRecord>()
    override suspend fun forPackage(packageName: String) = rows.values.filter { it.packageName == packageName }
    override suspend fun upsert(records: List<TimingRecord>) { records.forEach { rows[it.packageName to it.transitionKey] = it } }
    override suspend fun all(limit: Int) = rows.values.take(limit)
}
