package io.androllm.engine.utils

import android.content.Context

/**
 * Branch U it.2 (gate Z7): self-correction for the resident-weight share of
 * Gemma "E" models ([MemoryEstimator.residentWeightFraction]). The share is an
 * estimate (PLE tables stay memory-mapped), not a measurement. When reality
 * disagrees, the model falls back to the FULL share (1.0) on the next load:
 *
 * - after a load, the process PSS is compared with the estimate: PSS above
 *   estimate × [PSS_TOLERANCE] marks the model;
 * - an unload under memory pressure within [PRESSURE_WINDOW_MS] of a reduced-share
 *   load marks it;
 * - an out-of-memory failure while loading with a reduced share marks it.
 *
 * Marks persist (SharedPreferences once [attach]ed; in memory otherwise).
 */
object ResidentShareMemory {

    /** Persistence of the "use the full share" marks, keyed by model file name. */
    interface Store {
        fun isMarked(key: String): Boolean
        fun mark(key: String)
    }

    private class MemoryStore : Store {
        private val marks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        override fun isMarked(key: String) = key in marks
        override fun mark(key: String) { marks += key }
    }

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun isMarked(key: String) = prefs.getBoolean(key, false)
        override fun mark(key: String) { prefs.edit().putBoolean(key, true).apply() }
    }

    /** What was loaded last (for the memory-pressure check). */
    data class LoadRecord(val key: String, val fraction: Double, val loadedAtMs: Long)

    @Volatile
    var store: Store = MemoryStore()

    @Volatile
    var current: LoadRecord? = null
        private set

    const val PREFS = "model_resident_share"
    const val PSS_TOLERANCE = 1.25
    const val PRESSURE_WINDOW_MS = 10 * 60_000L

    fun attach(context: Context) {
        if (store is MemoryStore) store = runCatching<Store> { PrefsStore(context) }.getOrDefault(store)
    }

    /** The share to use for this load: 1.0 once the model was marked. */
    fun effectiveFraction(key: String, estimated: Double): Double =
        if (estimated < 1.0 && store.isMarked(key)) 1.0 else estimated

    /**
     * Records a successful load. Returns true when the measured PSS exceeded the
     * estimate × tolerance for a reduced share (the model is marked).
     */
    fun afterLoad(key: String, fraction: Double, pssBytes: Long, estimateBytes: Long, nowMs: Long): Boolean {
        current = LoadRecord(key, fraction, nowMs)
        if (fraction >= 1.0 || pssBytes <= 0L || estimateBytes <= 0L) return false
        val over = pssBytes > estimateBytes * PSS_TOLERANCE
        if (over) store.mark(key)
        return over
    }

    /** Memory-pressure unload. Returns true when the current model got marked. */
    fun onMemoryPressure(nowMs: Long): Boolean {
        val rec = current ?: return false
        if (rec.fraction >= 1.0 || nowMs - rec.loadedAtMs > PRESSURE_WINDOW_MS) return false
        store.mark(rec.key)
        return true
    }

    /** Out-of-memory while loading with [fraction]. */
    fun onLoadOom(key: String, fraction: Double) {
        if (fraction < 1.0) store.mark(key)
    }

    /** Test hook. */
    internal fun resetForTest(newStore: Store) {
        store = newStore
        current = null
    }
}
