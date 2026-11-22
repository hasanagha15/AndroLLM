package io.androllm.core.models.catalog

import io.androllm.core.common.Result
import java.io.File

/**
 * Where the active catalog was loaded from. BUNDLED ships with the APK, SAVED is a
 * previously fetched catalog persisted to app files, REMOTE was just fetched and applied.
 */
enum class CatalogSource { BUNDLED, SAVED, REMOTE }

sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(
        val catalog: CatalogFile,
        val source: CatalogSource,
        val warnings: List<String> = emptyList()
    ) : CatalogState

    data class Failed(val message: String) : CatalogState
}

/**
 * Fetches the latest catalog document from a remote host. Implemented in core:network.
 * Declared here so core:models stays free of any HTTP dependency.
 */
interface CatalogRemoteSource {
    suspend fun fetchCatalogJson(): Result<String>
}

/**
 * Pure catalog loading/merging logic, kept free of Android dependencies so it can be
 * unit-tested on the JVM.
 */
object CatalogLoader {

    /** A saved/remote catalog dated further ahead than this is rejected (clock skew allowance). */
    const val MAX_FUTURE_SKEW_MS: Long = 24L * 60 * 60 * 1000

    fun load(
        savedJson: String?,
        bundledJson: String,
        nowMs: Long = System.currentTimeMillis()
    ): CatalogState {
        val bundled = loadBundled(bundledJson)
        if (!savedJson.isNullOrBlank()) {
            val saved = apply(savedJson, CatalogSource.SAVED, nowMs)
            if (saved is CatalogState.Ready) {
                // A saved catalog older than the one shipped in this APK must
                // not shadow it (e.g. after an app update with a fixed catalog).
                val bundledReady = bundled as? CatalogState.Ready
                if (bundledReady == null ||
                    saved.catalog.generatedAt >= bundledReady.catalog.generatedAt
                ) {
                    return saved
                }
            }
        }
        return bundled
    }

    private fun loadBundled(bundledJson: String): CatalogState {
        return try {
            val result = CatalogParser.parse(bundledJson)
            val report = CatalogValidator.validate(result.catalog.models)
            if (report.errors.isNotEmpty()) {
                CatalogState.Failed("Bundled catalog failed validation: ${report.errors.first()}")
            } else {
                CatalogState.Ready(result.catalog, CatalogSource.BUNDLED, result.warnings + report.warnings)
            }
        } catch (e: IllegalArgumentException) {
            CatalogState.Failed("Bundled catalog is invalid: ${e.message}")
        }
    }

    /**
     * Parses and validates a catalog document. Returns Ready only when the document
     * parses cleanly and passes validation; otherwise Failed with the reason, so the
     * caller can keep the previous catalog.
     */
    fun apply(
        jsonText: String,
        source: CatalogSource,
        nowMs: Long = System.currentTimeMillis()
    ): CatalogState {
        return try {
            val result = CatalogParser.parse(jsonText)
            val report = CatalogValidator.validate(result.catalog.models)
            val unpinned = if (source == CatalogSource.BUNDLED) {
                emptyList()
            } else {
                CatalogPinPolicy.problems(result.catalog.models)
            }
            if (report.errors.isNotEmpty()) {
                CatalogState.Failed("Catalog failed validation: ${report.errors.first()}")
            } else if (source != CatalogSource.BUNDLED && result.catalog.models.isEmpty()) {
                // An empty saved/remote catalog would hide every model until a newer one appears.
                CatalogState.Failed("Catalog rejected: it lists no models")
            } else if (source != CatalogSource.BUNDLED && result.catalog.generatedAt > nowMs + MAX_FUTURE_SKEW_MS) {
                // A far-future date would make every later (real) catalog look "older".
                CatalogState.Failed("Catalog rejected: generatedAt ${result.catalog.generatedAt} is in the future")
            } else if (unpinned.isNotEmpty()) {
                // Fork policy: a saved/remote catalog with mutable URLs (e.g. the
                // upstream catalog's resolve/main links) is never applied.
                CatalogState.Failed(
                    "Catalog rejected: ${unpinned.size} entr${if (unpinned.size == 1) "y is" else "ies are"} " +
                        "not pinned to an immutable revision (${unpinned.first()})"
                )
            } else {
                CatalogState.Ready(result.catalog, source, result.warnings + report.warnings)
            }
        } catch (e: IllegalArgumentException) {
            CatalogState.Failed(e.message ?: "Invalid catalog document")
        }
    }

    /**
     * True when a freshly fetched [remote] catalog may replace the [current]
     * one: never move backwards in time (equal dates are accepted so the same
     * catalog can be re-applied).
     */
    fun isAcceptableUpdate(current: CatalogFile?, remote: CatalogFile): Boolean =
        current == null || remote.generatedAt >= current.generatedAt

    fun persistText(file: File, jsonText: String): Result<Unit> = io.androllm.core.common.runCatching {
        file.parentFile?.mkdirs()
        file.writeText(jsonText)
    }
}
