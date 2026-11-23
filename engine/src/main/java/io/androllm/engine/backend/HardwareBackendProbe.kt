package io.androllm.engine.backend

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import timber.log.Timber

/**
 * Startup hardware probe — runs ONCE at engine initialization and produces the
 * [BackendCapabilities] that drive automatic backend selection and the
 * adaptive settings UI.
 *
 * Detects, in order of authority:
 *  1. SoC vendor (Qualcomm / MediaTek / Google Tensor / Samsung) from the
 *     standard `ro.soc.*` / `ro.board.*` / `ro.hardware*` system properties.
 *  2. Android Neural Networks API availability (package-manager feature).
 *  3. GPU identity from `ro.hardware.egl` (+ board-platform heuristics).
 *  4. LiteRT vendor dispatch libraries (`libLiteRtDispatch_*.so`) reachable
 *     from the app's native library dir — the ONLY gate that makes the NPU
 *     delegate actually selectable.
 *
 * Every step is best-effort: a failing step degrades to \"unknown\", never
 * throws, and never breaks the app. On devices without an NPU driver the
 * probe reports `npuUsable = false` and the app behaves exactly as it does
 * today.
 */
object HardwareBackendProbe {

    private const val PROP_SOC_MANUFACTURER = "ro.soc.manufacturer"
    private const val PROP_SOC_MODEL = "ro.soc.model"
    private const val PROP_HARDWARE = "ro.hardware"
    private const val PROP_BOARD_PLATFORM = "ro.board.platform"
    private const val PROP_PRODUCT_BOARD = "ro.product.board"
    private const val PROP_CHIPNAME = "ro.hardware.chipname"
    private const val PROP_EGL = "ro.hardware.egl"

    /** Android feature flag for Neural Networks API presence. */
    private const val FEATURE_NEURAL_NETWORKS = "android.hardware.neuralnetworks"

    /**
     * Runs the full probe against the real device.
     */
    fun probe(context: Context): BackendCapabilities {
        val props = readSystemProperties()
        val nnApi = runCatching {
            context.packageManager.hasSystemFeature(FEATURE_NEURAL_NETWORKS)
        }.getOrDefault(false)
        val libDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
        val extracted = runCatching { File(libDir ?: "").list() ?: emptyArray() }
            .getOrDefault(emptyArray())
            .filter { it.startsWith("libLiteRtDispatch_") && it.endsWith(".so") }
        // Uncompressed jniLibs (16 KB packaging): nativeLibraryDir is empty, so
        // also look at the APK's lib/<abi>/ entries (central directory only).
        val dispatchLibs = extracted.ifEmpty { NpuLibraryDir.bundledDispatchLibs(context) }
        // Dispatch ABI: read the vendor lib's ELF symbol table (never dlopen).
        val abi = dispatchLibs.firstOrNull()?.let { lib ->
            val bytes = if (extracted.isNotEmpty()) {
                runCatching { File(libDir, lib).readBytes() }.getOrNull()
            } else {
                readApkLib(context, lib)
            }
            DispatchAbiCheck.check(bytes)
        }
        if (abi != null && abi != DispatchAbiCheck.Result.SUPPORTED) {
            Timber.w(
                "[NPU] ${dispatchLibs.first()} does not export ${DispatchAbiCheck.REQUIRED_SYMBOL} " +
                    "($abi) — incompatible with the bundled LiteRT-LM runtime; NPU disabled"
            )
        }
        return fromProperties(
            props,
            nnApiAvailable = nnApi,
            dispatchLibraries = dispatchLibs,
            dispatchAbi = abi ?: DispatchAbiCheck.Result.UNREADABLE,
            bundledHexagonArchs = bundledHexagonArchs()
        )
    }

    /** Bytes of `lib/<abi>/<name>` inside the APK (uncompressed jniLibs), or null. */
    private fun readApkLib(context: Context, name: String): ByteArray? = runCatching {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val entry = apk.getEntry("lib/$abi/$name") ?: return@use null
            apk.getInputStream(entry).use { it.readBytes() }
        }
    }.getOrNull()

    /** Hexagon archs the APK ships a QNN skel for (from [NpuLibraryDir.SKEL_LIB], e.g. V81). */
    fun bundledHexagonArchs(): Set<String> =
        Regex("""V\d\d""").findAll(NpuLibraryDir.SKEL_LIB).map { it.value }.toSet()

    /**
     * Hexagon HTP arch of well-known Snapdragon SoCs (`ro.soc.model`). Unknown
     * models return null and are not blocked (the runtime decides).
     */
    fun hexagonArchFor(socModel: String?): String? = when (socModel?.uppercase()?.trim()) {
        "SM8450", "SM8475" -> "V69"
        "SM8550" -> "V73"
        "SM8650" -> "V75" // Snapdragon 8 Gen 3 (Galaxy S24 Ultra)
        "SM8750" -> "V79"
        "SM8845", "SM8850" -> "V81"
        else -> null
    }

    /**
     * Pure probe implementation (unit-testable). [dispatchLibraries] is the
     * list of `.so` files found in the native library dir.
     */
    fun fromProperties(
        props: Map<String, String>,
        nnApiAvailable: Boolean = false,
        dispatchLibraries: List<String> = emptyList(),
        dispatchAbi: DispatchAbiCheck.Result = DispatchAbiCheck.Result.SUPPORTED,
        bundledHexagonArchs: Set<String> = emptySet()
    ): BackendCapabilities {
        val vendor = NpuVendor.detect(
            socManufacturer = props[PROP_SOC_MANUFACTURER],
            socModel = props[PROP_SOC_MODEL],
            hardware = props[PROP_HARDWARE],
            boardPlatform = props[PROP_BOARD_PLATFORM],
            productBoard = props[PROP_PRODUCT_BOARD],
            chipName = props[PROP_CHIPNAME]
        )

        val (gpuName, gpuVendor) = detectGpu(props[PROP_EGL], props[PROP_BOARD_PLATFORM], props[PROP_PRODUCT_BOARD])

        // "The SoC has an NPU block" — every modern Qualcomm/MediaTek/Google/
        // Samsung SoC does. The NNAPI feature flag is deliberately NOT part of
        // this decision: many OEM builds (ColorOS/OneUI/etc.) omit it even on
        // NPU-equipped devices, so gating on it would lock NPU out forever.
        // NNAPI stays in the probe as informational diagnostics only.
        val npuAvailable = vendor.detected
        // The vendor dispatch library must be physically reachable for the
        // LiteRT NPU delegate to initialize — hardware presence alone is not
        // enough. An UNKNOWN vendor with a generic dispatch lib present still
        // counts as usable (the runtime resolves it).
        // Case-insensitive vendor match: Google's prebuilt dispatch libraries
        // are `libLiteRtDispatch_Qualcomm.so` (capital Q) while the vendor id
        // is lowercase — a case-sensitive compare would lock NPU out forever.
        val dispatchFound = dispatchLibraries.any { lib ->
            if (vendor.detected) lib.contains(vendor.vendorId, ignoreCase = true) else true
        }
        // The dispatch lib must speak the runtime's dispatch ABI (LiteRT-LM
        // 0.19.x: LiteRtDispatchQueryInterface). A legacy (2.2.0-era) lib or
        // one we could not read can never initialize — don't offer NPU, so
        // AUTO never tries it (and never risks a native abort in that path).
        val abiOk = dispatchAbi == DispatchAbiCheck.Result.SUPPORTED
        // Qualcomm: the APK ships the QNN skel for specific Hexagon archs
        // only (V81 today). A known SoC with a different arch (e.g. SM8650 /
        // V75, Galaxy S24 Ultra) cannot run the bundled skel.
        val socArch = if (vendor.vendorId.equals("qualcomm", ignoreCase = true)) {
            hexagonArchFor(props[PROP_SOC_MODEL])
        } else null
        val archOk = socArch == null || bundledHexagonArchs.isEmpty() || socArch in bundledHexagonArchs
        val npuUsable = npuAvailable && dispatchFound && abiOk && archOk

        return BackendCapabilities(
            cpuAvailable = true,
            gpuAvailable = true,
            npuAvailable = npuAvailable,
            npuUsable = npuUsable,
            gpuName = gpuName,
            gpuVendor = gpuVendor,
            npuName = vendor.displayName.ifBlank { null },
            npuVendor = vendor.displayName.ifBlank { null },
            npuAccelerator = vendor.acceleratorName.ifBlank { null },
            nnApiAvailable = nnApiAvailable,
            selectedBackend = io.androllm.engine.models.BackendType.CPU,
            probedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Best-effort GPU identity from the EGL implementation string and board
     * platform heuristics. Returns (name, vendor) — both nullable.
     */
    private fun detectGpu(egl: String?, boardPlatform: String?, productBoard: String?): Pair<String?, String?> {
        val eglLower = egl?.lowercase() ?: ""
        val platformLower = (boardPlatform ?: "").lowercase() + " " + (productBoard ?: "").lowercase()
        return when {
            eglLower.contains("adreno") || platformLower.contains("adreno") -> "Adreno" to "Qualcomm"
            eglLower.contains("mali") || platformLower.contains("mali") -> "Mali" to "ARM"
            eglLower.contains("xclipse") || platformLower.contains("xclipse") -> "Xclipse" to "Samsung"
            eglLower.contains("powervr") -> "PowerVR" to "Imagination"
            eglLower.contains("apple") -> "Apple GPU" to "Apple"
            eglLower.isNotBlank() -> egl to null
            // Snapdragon platforms almost always pair with Adreno.
            platformLower.contains("qcom") || platformLower.startsWith("sm") -> "Adreno" to "Qualcomm"
            // MediaTek platforms pair with Mali (or PowerVR on older MTK).
            platformLower.startsWith("mt") -> "Mali" to "ARM"
            else -> null to null
        }
    }

    /**
     * Reads the SoC/GPU system properties via reflection on the hidden
     * `android.os.SystemProperties` class. Best-effort: every read is guarded
     * and a failure contributes nothing (all-unknown capabilities).
     */
    private fun readSystemProperties(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val keys = listOf(
            PROP_SOC_MANUFACTURER, PROP_SOC_MODEL, PROP_HARDWARE,
            PROP_BOARD_PLATFORM, PROP_PRODUCT_BOARD, PROP_CHIPNAME, PROP_EGL
        )
        runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java)
            for (key in keys) {
                val value = runCatching { get.invoke(null, key) as? String }.getOrNull()
                if (!value.isNullOrBlank()) result[key] = value
            }
        }
        return result
    }
}
