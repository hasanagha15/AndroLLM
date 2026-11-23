package io.androllm.engine.backend

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * Directory holding the Qualcomm NPU (QNN) vendor libraries for LiteRT's
 * dispatch delegate and the Hexagon DSP (FastRPC, via `ADSP_LIBRARY_PATH`).
 *
 * Why this exists (16 KB page-size compatibility, branch g-16kb): the APK now
 * keeps native libs uncompressed and 16 KB zip-aligned (AGP
 * `useLegacyPackaging = false`, i.e. `extractNativeLibs=false`), which is what
 * Android 15+/17's page-size check expects. With that packaging
 * `applicationInfo.nativeLibraryDir` is EMPTY, but LiteRT's dispatch delegate
 * finds its vendor libs via a `readdir` of the directory passed to
 * `Backend.NPU(nativeLibraryDir)`, and the DSP loads the skel from a real file
 * on `ADSP_LIBRARY_PATH`. So the few NPU libs are staged once per app update
 * into an app-private directory:
 *  - ARM64 libs ([ARM_LIBS]) are copied out of the APK's `lib/<abi>/` entries
 *    (they stay loadable from the APK too, for anything that dlopens by name);
 *  - the Hexagon skel ([SKEL_LIB]) is NOT an ARM ELF — its 4 KB p_align would
 *    fail the platform's ELF alignment check if it lived under `lib/` — so it
 *    ships as an asset ([SKEL_ASSET]) and is only ever read by the DSP loader.
 *
 * If the libs are already extracted to `nativeLibraryDir` (legacy packaging),
 * that directory is used as before and nothing is copied.
 */
object NpuLibraryDir {
    val ARM_LIBS = listOf(
        "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so",
        "libQnnHtpV81Stub.so",
        "libQnnSystem.so"
    )
    const val SKEL_LIB = "libQnnHtpV81Skel.so"
    const val SKEL_ASSET = "npu/$SKEL_LIB"
    private const val STAGE_DIR = "npu-libs"
    private const val STAMP = ".stamp"

    @Volatile private var staged: String? = null
    private val lock = Any()

    /** Path that [ensureStaged] stages into (may not exist yet). Cheap; safe on the main thread. */
    fun stagingPath(context: Context): String = File(context.noBackupFilesDir, STAGE_DIR).absolutePath

    /** True when this device can use the bundled Qualcomm NPU libs at all. */
    fun isQualcommSoc(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) ||
            Build.SOC_MANUFACTURER.contains("qualcomm", ignoreCase = true)

    /** `libLiteRtDispatch_*.so` names bundled in the APK's `lib/<abi>/` (no extraction). */
    fun bundledDispatchLibs(context: Context): List<String> = runCatching {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val prefix = "lib/$abi/"
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith(prefix + "libLiteRtDispatch_") && it.endsWith(".so") }
                .map { it.removePrefix(prefix) }
                .toList()
        }
    }.getOrDefault(emptyList())

    /**
     * Returns the directory holding the dispatch lib, staging it first if
     * needed (blocking I/O, ~20 MB once per install/update — call off the main
     * thread). Falls back to `nativeLibraryDir` on any failure.
     */
    fun ensureStaged(context: Context): String? {
        staged?.let { return it }
        synchronized(lock) {
            staged?.let { return it }
            val nativeDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
            // Legacy packaging (libs extracted): keep the old behaviour.
            if (nativeDir != null && File(nativeDir, ARM_LIBS[0]).isFile) {
                staged = nativeDir
                return nativeDir
            }
            if (!isQualcommSoc()) {
                staged = nativeDir
                return nativeDir
            }
            val result = runCatching { stage(context) }
                .onFailure { Timber.w(it, "[NPU] staging vendor libs failed") }
                .getOrNull()
            staged = result ?: nativeDir
            return staged
        }
    }

    private fun stage(context: Context): String {
        val dir = File(stagingPath(context))
        val stampValue = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0") + "|" + context.applicationInfo.sourceDir
        val stampFile = File(dir, STAMP)
        if (stampFile.isFile && stampFile.readText() == stampValue &&
            (ARM_LIBS + SKEL_LIB).all { File(dir, it).isFile }
        ) {
            return dir.absolutePath
        }
        dir.mkdirs()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val t0 = System.currentTimeMillis()
        var copied = 0
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            for (name in ARM_LIBS) {
                val entry = apk.getEntry("lib/$abi/$name") ?: continue
                apk.getInputStream(entry).use { input -> writeAtomically(File(dir, name)) { input.copyTo(it) } }
                copied++
            }
        }
        runCatching {
            context.assets.open(SKEL_ASSET).use { input ->
                writeAtomically(File(dir, SKEL_LIB)) { input.copyTo(it) }
            }
            copied++
        }.onFailure { Timber.w("[NPU] skel asset $SKEL_ASSET missing: ${it.message}") }
        stampFile.writeText(stampValue)
        Timber.i("[NPU] staged $copied vendor libs into $dir in ${System.currentTimeMillis() - t0} ms")
        return dir.absolutePath
    }

    private inline fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use(write)
        target.delete()
        if (!tmp.renameTo(target)) throw java.io.IOException("rename $tmp -> $target failed")
        // Dynamic-code-loading hygiene (Android 14+): loaded files read-only.
        target.setReadOnly()
    }
}
