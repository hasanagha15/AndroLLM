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
     * Until the KV-cache cost is measured on device (F1 gate N2), a load that
     * may run on the GPU (the memory-fit guard looks at RAM, not GPU memory)
     * or a model above [LARGE_MODEL_BILLIONS] parameters is capped here.
     */
    const val UNMEASURED_CAP = 8192
    const val LARGE_MODEL_BILLIONS = 3.0f

    /**
     * [catalogContext]: the model's catalog/metadata context (0 = unknown).
     * [totalRamGb]: ActivityManager total memory in GB (0 = unknown).
     * [mayUseGpu]: the backend request is not CPU-only (AUTO/GPU/NPU).
     * [parametersLabel]: catalog parameter label ("0.6B", "4B"); blank = unknown.
     */
    fun resolve(
        catalogContext: Int,
        totalRamGb: Float,
        mayUseGpu: Boolean = false,
        parametersLabel: String = ""
    ): Int {
        val catalog = catalogContext.takeIf { it > 0 } ?: FALLBACK_CONTEXT
        var cap = ramCap(totalRamGb)
        val billions = parseBillions(parametersLabel)
        if (mayUseGpu || (billions != null && billions > LARGE_MODEL_BILLIONS)) {
            cap = minOf(cap, UNMEASURED_CAP)
        }
        return minOf(catalog, cap).coerceAtLeast(MIN_CONTEXT)
    }

    /** "4B" → 4.0, "0.6B" → 0.6, "E2B" → 2.0, "270M" → 0.27; null when unknown. */
    fun parseBillions(label: String): Float? {
        val m = PARAMS_REGEX.find(label.uppercase()) ?: return null
        val value = m.groupValues[1].toFloatOrNull() ?: return null
        return if (m.groupValues[2] == "M") value / 1000f else value
    }

    private val PARAMS_REGEX = Regex("([0-9]+(?:[.][0-9]+)?) *([BM])")
}
