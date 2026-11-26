package io.androllm.engine.utils

import android.content.Context

/**
 * Branch U it.2/it.3 (gate Z7, B3): self-correction for the resident-weight share
 * of Gemma "E" models ([MemoryEstimator.residentWeightFraction]). The share is an
 * estimate (PLE tables stay memory-mapped), not a measurement. When reality
 * disagrees, the model falls back to the FULL share (1.0), but only on solid
 * evidence and never forever:
 *
 * - **What is measured**: private dirty memory + graphics (Debug.MemoryInfo),
 *   i.e. memory WITHOUT file-backed pages. The mmapped `.litertlm` pages are
 *   exactly what the reduced share excludes, so a full PSS (which counts them)
 *   is never compared with the estimate.
 * - **Which pressure counts**: only TRIM_MEMORY_RUNNING_CRITICAL or onLowMemory
 *   while the app is in the FOREGROUND, within [PRESSURE_WINDOW_MS] of a
 *   reduced-share load. TRIM_MEMORY_COMPLETE (cached in the background) never.
 * - **Two events**: one event never flips the share; the full share applies
 *   after [EVENTS_TO_MARK] events (overshoot, foreground pressure, load OOM).
 * - **Expiry**: events and marks expire after [MARK_TTL_MS] (7 days) and when
 *   the app version changes; [reset] (Developer screen) clears everything.
 */
object ResidentShareMemory {

    /** Raw persistence (SharedPreferences once [attach]ed; in memory otherwise). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun clear()
    }

    private class MemoryStore : Store {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun clear() = map.clear()
    }

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun clear() { prefs.edit().clear().apply() }
    }

    /** One model's evidence: event count, last event time, app version it was recorded under. */
    data class Evidence(val count: Int, val lastMs: Long, val version: Long) {
        fun encode(): String = "$count;$lastMs;$version"

        companion object {
            fun decode(s: String?): Evidence? {
                val parts = s?.split(';')?.takeIf { it.size == 3 } ?: return null
                val c = parts[0].toIntOrNull() ?: return null
                val t = parts[1].toLongOrNull() ?: return null
                val v = parts[2].toLongOrNull() ?: return null
                return Evidence(c, t, v)
            }
        }
    }

    /** What was loaded last (for the memory-pressure check). */
    data class LoadRecord(val key: String, val fraction: Double, val loadedAtMs: Long)

    /** Pressure sources reported by the application. */
    enum class Pressure { RUNNING_CRITICAL, LOW_MEMORY, COMPLETE, OTHER }

    @Volatile
    var store: Store = MemoryStore()

    /** App version the evidence is valid for (versionCode; set by [attach]). */
    @Volatile
    var appVersion: Long = 0L

    @Volatile
    var current: LoadRecord? = null
        private set

    const val PREFS = "model_resident_share"
    const val TOLERANCE = 1.25
    const val PRESSURE_WINDOW_MS = 10 * 60_000L
    const val EVENTS_TO_MARK = 2
    const val MARK_TTL_MS = 7L * 24 * 60 * 60_000L

    fun attach(context: Context) {
        if (store is MemoryStore) store = runCatching<Store> { PrefsStore(context) }.getOrDefault(store)
        appVersion = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(appVersion)
    }

    private fun valid(e: Evidence?, nowMs: Long): Evidence? =
        e?.takeIf { it.version == appVersion && nowMs - it.lastMs in 0..MARK_TTL_MS }

    /** True when the model must use the full share right now. */
    fun isMarked(key: String, nowMs: Long): Boolean =
        (valid(Evidence.decode(store.get(key)), nowMs)?.count ?: 0) >= EVENTS_TO_MARK

    /** The share to use for this load: 1.0 while the model is marked. */
    fun effectiveFraction(key: String, estimated: Double, nowMs: Long = System.currentTimeMillis()): Double =
        if (estimated < 1.0 && isMarked(key, nowMs)) 1.0 else estimated

    /** Adds one event; returns true when the model is marked afterwards. */
    fun recordEvent(key: String, nowMs: Long): Boolean {
        val prev = valid(Evidence.decode(store.get(key)), nowMs)
        val next = Evidence((prev?.count ?: 0) + 1, nowMs, appVersion)
        store.put(key, next.encode())
        return next.count >= EVENTS_TO_MARK
    }

    /**
     * After a successful load. [nonFileBytes] = private dirty + graphics (no
     * file-backed pages). An overshoot of the estimate counts as ONE event.
     * Returns true when an event was recorded.
     */
    fun afterLoad(key: String, fraction: Double, nonFileBytes: Long, estimateBytes: Long, nowMs: Long): Boolean {
        current = LoadRecord(key, fraction, nowMs)
        if (fraction >= 1.0 || nonFileBytes <= 0L || estimateBytes <= 0L) return false
        if (nonFileBytes <= estimateBytes * TOLERANCE) return false
        recordEvent(key, nowMs)
        return true
    }

    /**
     * Memory pressure reported by the application. Counts only for
     * RUNNING_CRITICAL / LOW_MEMORY in the foreground, soon after a reduced-share
     * load. Returns true when an event was recorded.
     */
    fun onMemoryPressure(pressure: Pressure, foreground: Boolean, nowMs: Long): Boolean {
        if (pressure != Pressure.RUNNING_CRITICAL && pressure != Pressure.LOW_MEMORY) return false
        if (!foreground) return false
        val rec = current ?: return false
        if (rec.fraction >= 1.0 || nowMs - rec.loadedAtMs !in 0..PRESSURE_WINDOW_MS) return false
        recordEvent(rec.key, nowMs)
        return true
    }

    /** Out-of-memory while loading with a reduced [fraction] (one event). */
    fun onLoadOom(key: String, fraction: Double, nowMs: Long = System.currentTimeMillis()) {
        if (fraction < 1.0) recordEvent(key, nowMs)
    }

    /** Developer screen: forget every event and mark. */
    fun reset() {
        store.clear()
    }

    /** Test hook. */
    internal fun resetForTest(newStore: Store, version: Long = 1L) {
        store = newStore
        appVersion = version
        current = null
    }
}
