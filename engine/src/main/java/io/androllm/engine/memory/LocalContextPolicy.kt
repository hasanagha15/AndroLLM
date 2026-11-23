package io.androllm.engine.memory

/**
 * Requested context window for a local model load when the caller did not ask
 * for an explicit one (ModelLoadConfig.contextLength == 0, the normal path
 * from the Models screen).
 *
 * Before (AndroLLM fork F1) the engine requested a fixed 4096 for the memory
 * fit and 8192 for the container clamp, ignoring the model's catalog context.
 * Now the request is the catalog context capped by the device's total RAM:
 *
 * | total RAM   | cap    |
 * |-------------|--------|
 * | < 4 GB      | 2048   |
 * | < 6 GB      | 4096   |
 * | < 10 GB     | 8192   |
 * | >= 10 GB    | 16384  |
 *
 * The result is still only a REQUEST: the aggressive memory-fit guard may
 * lower it under memory pressure, and a LiteRT-LM container's own
 * max_num_tokens (a fixed KV-cache size baked in at conversion) always wins
 * when it is smaller.
 */
object LocalContextPolicy {

    /** Used when the model carries no catalog context. */
    const val FALLBACK_CONTEXT = 4096

    /** Never request less than this (chat template + one short turn). */
    const val MIN_CONTEXT = 1024

    fun ramCap(totalRamGb: Float): Int = when {
        totalRamGb <= 0f -> FALLBACK_CONTEXT // unknown RAM: stay conservative
        totalRamGb < 4f -> 2048
        totalRamGb < 6f -> 4096
        totalRamGb < 10f -> 8192
        else -> 16384
    }

    /**
     * [catalogContext]: the model's catalog/metadata context (0 = unknown).
     * [totalRamGb]: ActivityManager total memory in GB (0 = unknown).
     */
    fun resolve(catalogContext: Int, totalRamGb: Float): Int {
        val catalog = catalogContext.takeIf { it > 0 } ?: FALLBACK_CONTEXT
        return minOf(catalog, ramCap(totalRamGb)).coerceAtLeast(MIN_CONTEXT)
    }
}
