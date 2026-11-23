package io.androllm.core.accessibility.analyzer

import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.accessibility.controller.UiContextSnapshot
import io.androllm.core.accessibility.controller.UIStateTracker
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.finder.AccessibilityNodeFinder
import io.androllm.core.accessibility.ocr.OcrEngine
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.tree.UiTreeBuilder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces [UiScreenSnapshot]s: reads the live window, builds the semantic
 * tree, detects loading state and focus, and merges OCR text when a vision
 * engine is available and enabled. This is the single source of truth the
 * planners and the LLM see.
 */
@Singleton
class ScreenAnalyzer @Inject constructor(
    private val settingsStore: AccessibilitySettingsStore,
    private val uiState: UIStateTracker,
    private val ocrEngine: OcrEngine,
    private val debug: AccessibilityDebugStore
) {

    suspend fun read(controller: AccessibilityController): UiScreenSnapshot {
        val root = controller.onMain { controller.serviceOrNull()?.rootInActiveWindow }
        val activePkg = root?.let { controller.onMain { it.packageName } }?.toString()
        val context = uiState.snapshot()
        // K: banking/payment apps, Tesla and WhatsApp are never read (no tree,
        // no OCR, no tracker text). Fail-closed when the active window is unknown.
        val gate = readGate(activePkg, context, controller.sensitiveExtras())
        gate.hiddenReason?.let { reason ->
            return UiScreenSnapshot.hidden(gate.packageName, reason, gate.hiddenRetryable)
        }
        val uiRoot = root?.let { controller.onMain { UiTreeBuilder.build(it) } }

        val loading = uiRoot?.flatten()?.any { it.type == UiElementType.PROGRESS } ?: false
        uiState.setLoading(loading)

        val focused = uiRoot?.let { AccessibilityNodeFinder.findFocused(it) }?.label
            ?: gate.trackerFocused
        val selected = uiRoot?.flatten()?.firstOrNull { it.selected }?.label
            ?: gate.trackerSelected

        var ocrLines = emptyList<String>()
        val settings = settingsStore.current()
        if (settings.ocrEnabled && ocrEngine.isAvailable) {
            controller.captureScreenshot()?.let { bitmap ->
                ocrLines = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault(emptyList())
            }
        }

        val snapshot = UiScreenSnapshot(
            packageName = gate.packageName,
            windowTitle = context.windowTitle,
            root = uiRoot,
            focusedText = focused,
            selectedText = selected,
            loading = loading,
            ocrLines = ocrLines
        )
        // Developer mode: keep the last full screen dump around.
        if (settings.developerMode) {
            debug.recordScreenDump(snapshot.describe(120))
        }
        return snapshot
    }
}

/** Outcome of the pre-read privacy gate (K). */
internal data class ReadGate(
    val hiddenReason: String?,
    val hiddenRetryable: Boolean,
    val packageName: String,
    val trackerFocused: String,
    val trackerSelected: String
)

/**
 * Pure pre-read gate. [activePackage] = package of rootInActiveWindow (null =
 * unknown). Refuses when the active or the last tracked app is protected;
 * refuses (retryable) when the active window is unknown; otherwise passes the
 * tracker's focused/selected text only when it belongs to the active app.
 */
internal fun readGate(activePackage: String?, ctx: UiContextSnapshot, extras: Set<String>): ReadGate {
    val pkg = activePackage?.takeIf { it.isNotBlank() } ?: ctx.currentPackage
    SensitiveAppGuard.screenReadGate(activePackage, ctx.currentPackage, extras)?.let {
        return ReadGate(it, false, pkg, "", "")
    }
    if (activePackage.isNullOrBlank()) {
        return ReadGate(SensitiveAppGuard.UNKNOWN_WINDOW_REASON, true, pkg, "", "")
    }
    val sameApp = ctx.textPackage.isNotBlank() && ctx.textPackage == activePackage
    return ReadGate(
        hiddenReason = null,
        hiddenRetryable = false,
        packageName = pkg,
        trackerFocused = if (sameApp) ctx.focusedText else "",
        trackerSelected = if (sameApp) ctx.selectedText else ""
    )
}
