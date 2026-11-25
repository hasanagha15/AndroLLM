package io.androllm.engine.utils

import java.io.File

/**
 * Branch P (gate N8): keeps the per-context runtime caches from growing
 * without bound.
 *
 * LiteRT-LM writes its caches (XNNPACK weights, `_mldrift_program_cache.bin`,
 * `_mldrift_weight_cache.bin`) into the engine's cacheDir, named
 * `<model file name>_<mtime>_<size>…`. Branch P gives every context size its
 * own directory (`<root>/ctx<N>`) because the serialized GPU programs depend
 * on maxNumTokens. Each model therefore keeps at most [keep] context
 * directories' worth of cache files: the one being loaded plus the most
 * recently used other(s). The model's legacy files directly in the old
 * flat cacheDir (written before branch P, never read again) are removed.
 * Files of other models are never touched; empty ctx directories are removed.
 */
object RuntimeCachePruner {

    data class Pruned(val deletedFiles: Int, val freedBytes: Long)

    /**
     * @param root `<cacheDir>/litertlm`
     * @param legacyDir the flat cacheDir used before branch P (may be null)
     * @param modelFileName file name of the model being loaded (cache prefix)
     * @param currentDir the ctx directory about to be used (always kept)
     */
    fun prune(root: File, legacyDir: File?, modelFileName: String, currentDir: File, keep: Int = 2): Pruned {
        var deleted = 0
        var freed = 0L
        val prefix = modelFileName + "_"
        fun isModelCache(f: File) = f.isFile && f.name.startsWith(prefix) &&
            (f.name.endsWith(".xnnpack_cache") || f.name.endsWith(".bin"))
        fun delete(f: File) {
            val size = f.length()
            if (f.delete()) { deleted++; freed += size }
        }

        // 1. Legacy flat-layout files of this model.
        legacyDir?.listFiles()?.filter { isModelCache(it) }?.forEach { delete(it) }

        // 2. Per-context directories: keep the current one + the newest others.
        val ctxDirs = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("ctx") }.orEmpty()
        val withModel = ctxDirs.mapNotNull { dir ->
            val files = dir.listFiles()?.filter { isModelCache(it) }.orEmpty()
            if (files.isEmpty()) null else dir to files
        }
        val others = withModel
            .filter { it.first.canonicalPath != currentDir.canonicalPath }
            .sortedByDescending { (_, files) -> files.maxOf { it.lastModified() } }
        others.drop((keep - 1).coerceAtLeast(0)).forEach { (_, files) -> files.forEach { delete(it) } }

        // 3. Empty ctx directories (never the current one).
        ctxDirs.filter { it.canonicalPath != currentDir.canonicalPath && it.listFiles().isNullOrEmpty() }
            .forEach { it.delete() }
        return Pruned(deleted, freed)
    }
}
