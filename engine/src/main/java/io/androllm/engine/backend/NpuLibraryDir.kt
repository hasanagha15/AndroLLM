package io.androllm.engine.backend

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
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
 * Every copy is size + CRC32 verified and fsynced before an atomic rename; the
 * stamp (versionCode | lastUpdateTime | sourceDir + per-file size/CRC) is
 * written last, the same way. A failed attempt is not latched: later calls
 * retry, up to [MAX_ATTEMPTS] per process.
 *
 * If the libs are already extracted to `nativeLibraryDir` (legacy packaging),
 * or the device is not a Qualcomm SoC, or the APK carries no Qualcomm dispatch
 * lib, `nativeLibraryDir` is used as before and any stale stage dir is removed.
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
    internal const val STAGE_DIR = "npu-libs"
    internal const val STAMP = ".stamp"
    internal const val MAX_ATTEMPTS = 3

    @Volatile private var staged: String? = null
    private var failedAttempts = 0
    private val lock = Any()

    /** One file to stage: [expectedSize]/[expectedCrc] < 0 means "unknown". */
    internal class Source(
        val name: String,
        val expectedSize: Long,
        val expectedCrc: Long,
        val open: () -> InputStream
    )

    /** Path that [ensureStaged] stages into (may not exist yet). Cheap; safe on the main thread. */
    fun stagingPath(context: Context): String = File(context.noBackupFilesDir, STAGE_DIR).absolutePath

    /** True when this device is a Qualcomm SoC (the bundled QNN libs can only work there). */
    fun isQualcommSoc(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        val soc = if (sdk >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
        return isQualcommSoc(
            sdk = sdk,
            socManufacturer = soc,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            hasCdspRpc = { File("/vendor/lib64/libcdsprpc.so").exists() }
        )
    }

    /**
     * Pure decision (unit-tested). Android 12+: `Build.SOC_MANUFACTURER` is
     * authoritative. Below 12: `Build.HARDWARE`/`Build.BOARD` heuristics
     * ("qcom" or a Snapdragon platform name), else the presence of Qualcomm's
     * FastRPC library. Default false.
     */
    internal fun isQualcommSoc(
        sdk: Int,
        socManufacturer: String?,
        hardware: String?,
        board: String?,
        hasCdspRpc: () -> Boolean
    ): Boolean {
        if (sdk >= 31) {
            val m = socManufacturer.orEmpty()
            return m.equals("QTI", ignoreCase = true) || m.contains("qualcomm", ignoreCase = true)
        }
        val hw = hardware.orEmpty().lowercase()
        val bd = board.orEmpty().lowercase()
        if (hw.startsWith("qcom") || bd == "qcom") return true
        if (QCOM_PLATFORM.matches(hw) || QCOM_PLATFORM.matches(bd)) return true
        return runCatching { hasCdspRpc() }.getOrDefault(false)
    }

    // Snapdragon platform codenames seen in Build.BOARD/HARDWARE on Android 9–11.
    private val QCOM_PLATFORM = Regex(
        "^(msm\\d+.*|sdm\\d+.*|sm\\d{4}.*|apq\\d+.*|qsd\\d+.*|" +
            "kona|lahaina|lito|bengal|trinket|atoll|holi|taro|kalama|pineapple|msmnile|sdmmagpie|talos)$"
    )

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
     * thread). Falls back to `nativeLibraryDir` when staging is not applicable
     * or failed; a failure is retried by later calls (at most [MAX_ATTEMPTS]).
     */
    fun ensureStaged(context: Context): String? {
        staged?.let { return it }
        synchronized(lock) {
            staged?.let { return it }
            val nativeDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
            val stageDir = File(stagingPath(context))
            val legacyExtracted = nativeDir != null && File(nativeDir, ARM_LIBS[0]).isFile
            if (legacyExtracted || !isQualcommSoc() || ARM_LIBS[0] !in bundledDispatchLibs(context)) {
                // Not applicable: use nativeLibraryDir as before; drop stale copies.
                if (stageDir.exists()) runCatching { stageDir.deleteRecursively() }
                staged = nativeDir
                return nativeDir
            }
            if (failedAttempts >= MAX_ATTEMPTS) return nativeDir
            return runCatching { stage(context, stageDir) }
                .onSuccess { staged = it; failedAttempts = 0 }
                .getOrElse {
                    failedAttempts++
                    Timber.w(it, "[NPU] staging vendor libs failed (attempt $failedAttempts/$MAX_ATTEMPTS)")
                    nativeDir
                }
        }
    }

    private fun stage(context: Context, dir: File): String {
        @Suppress("DEPRECATION")
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION") pkg.versionCode.toLong()
        }
        val identity = "$versionCode|${pkg.lastUpdateTime}|${context.applicationInfo.sourceDir}"
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val t0 = System.currentTimeMillis()
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val sources = ARM_LIBS.mapNotNull { name ->
                val entry = apk.getEntry("lib/$abi/$name") ?: return@mapNotNull null
                Source(name, entry.size, entry.crc) { apk.getInputStream(entry) }
            } + Source(SKEL_LIB, assetLength(context), -1L) { context.assets.open(SKEL_ASSET) }
            val copied = stageInto(dir, identity, sources)
            if (copied > 0) {
                Timber.i("[NPU] staged $copied vendor libs into $dir in ${System.currentTimeMillis() - t0} ms")
            }
        }
        return dir.absolutePath
    }

    /** Asset length when it is stored uncompressed (openFd works), else -1 (unknown). */
    private fun assetLength(context: Context): Long =
        runCatching { context.assets.openFd(SKEL_ASSET).use { it.length } }.getOrDefault(-1L)

    /**
     * Core staging (pure JVM, unit-tested): reuses [dir] when its stamp matches
     * [identity] and every file has the stamped size; otherwise removes stale
     * files and `*.tmp`, copies each source with size/CRC verification + fsync
     * + atomic rename, and writes the stamp last. Returns the number of files
     * copied (0 = reused). Throws on any verification/IO failure.
     */
    internal fun stageInto(dir: File, identity: String, sources: List<Source>): Int {
        if (isStampValid(dir, identity, sources)) return 0
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        // Clean: stale libs, leftovers of a crashed copy, the old stamp.
        val keep = sources.map { it.name }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || f.name == STAMP || f.name !in keep) forceDelete(f)
        }
        val lines = StringBuilder(identity).append('\n')
        for (src in sources) {
            val (size, crc) = copyVerified(src, File(dir, src.name))
            lines.append(src.name).append(' ').append(size).append(' ').append(crc).append('\n')
        }
        val bytes = lines.toString().toByteArray()
        writeAtomically(File(dir, STAMP), readOnly = false) { it.write(bytes) }
        return sources.size
    }

    internal fun isStampValid(dir: File, identity: String, sources: List<Source>): Boolean {
        val stampFile = File(dir, STAMP)
        if (!stampFile.isFile) return false
        val lines = runCatching { stampFile.readLines() }.getOrNull() ?: return false
        if (lines.firstOrNull() != identity) return false
        val recorded = lines.drop(1).filter { it.isNotBlank() }.associate { line ->
            val p = line.split(' ')
            p[0] to Pair(p.getOrNull(1)?.toLongOrNull(), p.getOrNull(2)?.toLongOrNull())
        }
        return sources.all { src ->
            val rec = recorded[src.name] ?: return@all false
            val size = rec.first ?: return@all false
            val f = File(dir, src.name)
            f.isFile && f.length() == size &&
                (src.expectedSize < 0 || src.expectedSize == size) &&
                (src.expectedCrc < 0 || src.expectedCrc == rec.second)
        }
    }

    /** Copies [src] to [target] via `<name>.tmp`; verifies size + CRC32, fsyncs, renames. */
    private fun copyVerified(src: Source, target: File): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        writeAtomically(target, readOnly = true) { out ->
            src.open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    size += n
                }
            }
            if (src.expectedSize >= 0 && size != src.expectedSize) {
                throw IOException("${src.name}: size $size != expected ${src.expectedSize}")
            }
            if (src.expectedCrc >= 0 && crc.value != src.expectedCrc) {
                throw IOException("${src.name}: crc ${crc.value} != expected ${src.expectedCrc}")
            }
        }
        if (target.length() != size) throw IOException("${src.name}: on-disk size ${target.length()} != $size")
        return size to crc.value
    }

    private inline fun writeAtomically(target: File, readOnly: Boolean, write: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            if (target.exists()) forceDelete(target)
            if (!tmp.renameTo(target)) throw IOException("rename $tmp -> $target failed")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        // Dynamic-code-loading hygiene (Android 14+): loaded files read-only.
        if (readOnly) target.setReadOnly()
    }

    /** Read-only files cannot be deleted on some filesystems (and on Windows test hosts). */
    private fun forceDelete(f: File) {
        f.setWritable(true)
        f.delete()
    }
}
