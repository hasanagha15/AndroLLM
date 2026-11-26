package io.androllm.core.accessibility.controller

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.accessibility.AccessibilityAutomationService
import io.androllm.core.accessibility.analyzer.ScreenAnalyzer
import io.androllm.core.accessibility.analyzer.UiScreenSnapshot
import io.androllm.core.accessibility.debug.AccessibilityDebugStore
import io.androllm.core.accessibility.finder.LiveTree
import io.androllm.core.accessibility.finder.UiSelector
import io.androllm.core.accessibility.gestures.GestureExecutor
import io.androllm.core.accessibility.input.TextInputController
import io.androllm.core.accessibility.planner.SafetyClassifier
import io.androllm.core.accessibility.settings.AccessibilitySettingsStore
import io.androllm.core.accessibility.tree.UiElementType
import io.androllm.core.accessibility.util.MainThread
import io.androllm.core.tools.tool.impl.ToolIntents
import io.androllm.core.tools.uistep.UiStep
import io.androllm.core.tools.uistep.UiStepJournal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/** Outcome of a single UI action (tap / type / scroll / navigate). */
data class UiActionResult(
    val success: Boolean,
    val message: String,
    /**
     * False for refusals a retry or another tool cannot fix (SensitiveAppGuard):
     * the ui_* tools then return a non-retryable failure, so the coordinator
     * neither repeats the call nor tries an "alternative tool".
     */
    val retryable: Boolean = true,
    /**
     * Branch M: non-null when the RESOLVED element is a risky send / pay /
     * call / confirm step that was not approved for exactly this element.
     */
    val risky: RiskyElementGate.Decision.Refuse? = null
) {
    companion object {
        fun ok(msg: String) = UiActionResult(true, msg)
        fun fail(msg: String) = UiActionResult(false, msg)
        fun blocked(msg: String) = UiActionResult(false, msg, retryable = false)
        fun risky(refusal: RiskyElementGate.Decision.Refuse, verb: String = "ui_click") =
            UiActionResult(false, RiskyElementGate.refusalMessage(refusal, verb), retryable = false, risky = refusal)
    }
}

/**
 * The single facade of the accessibility automation engine. Tools, the
 * executor and the screen analyzer all talk to this class; it owns the
 * service binding, routes every node operation to the main thread and keeps
 * CPU low by caching nothing between actions (trees are cheap to rebuild and
 * never stale).
 */
@Singleton
class AccessibilityController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: AccessibilitySettingsStore,
    private val windowMonitor: WindowMonitor,
    private val uiState: UIStateTracker,
    private val screenAnalyzer: ScreenAnalyzer,
    private val debug: AccessibilityDebugStore,
    private val sensitiveApps: SensitiveAppStore
) {

    @Volatile private var service: AccessibilityAutomationService? = null

    private val gestures = GestureExecutor({ service }, debug)
    private val textInput = TextInputController(context, { service }, gestures, debug)

    /** True while the user has enabled the service in system settings. */
    val isConnected: Boolean get() = service != null

    internal fun serviceOrNull(): AccessibilityAutomationService? = service

    internal fun bind(service: AccessibilityAutomationService) {
        this.service = service
        debug.record("service", "connected")
    }

    internal fun unbind() {
        service = null
        debug.record("service", "disconnected")
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Branch W (gate N3): any UI event = the screen is alive (someone may be using it).
        lastEventAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        // Branch W3: input-like events (a new window, a scroll, a focus change) vs passive content changes.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_FOCUSED ->
                lastInputEventAtElapsedMs = lastEventAtElapsedMs
            else -> Unit
        }
        windowMonitor.onEvent(event)
        uiState.onEvent(event)
        // Branch N: playbook replay waits on these instead of fixed sleeps.
        val t = event.eventType
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            _uiEvents.value = _uiEvents.value + 1
        }
    }

    /** Branch W: elapsedRealtime of the last accessibility event of any type (0 = none yet). */
    @Volatile var lastEventAtElapsedMs: Long = 0L
        private set

    /** Branch W3: elapsedRealtime of the last input-like event (window state / windows / scroll / focus). */
    @Volatile var lastInputEventAtElapsedMs: Long = 0L
        private set

    private val _uiEvents = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Branch N: increments on every window-state / window-content change. */
    val uiEvents: kotlinx.coroutines.flow.StateFlow<Long> = _uiEvents

    // ── Branch N: playbook replay primitives (tree only, no screenshots) ────

    /**
     * Visible nodes of the active window, or null when the service is off /
     * no window. Protected apps (SensitiveAppGuard) yield an empty snapshot
     * with [NodeSnapshot.blockedReason] — their content is never read.
     */
    suspend fun nodeSnapshot(max: Int = 500): NodeSnapshot? {
        val root = onMain { service?.rootInActiveWindow } ?: return null
        val pkg = onMain { root.packageName?.toString() }
        guardReason(pkg)?.let { return NodeSnapshot(pkg, emptyList(), it) }
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1).toFloat()
        val h = dm.heightPixels.coerceAtLeast(1).toFloat()
        val nodes = onMain {
            val perClass = HashMap<String, Int>()
            val r = android.graphics.Rect()
            LiveTree.collect(root, max).mapIndexedNotNull { i, n ->
                if (!n.isVisibleToUser) return@mapIndexedNotNull null
                val cls = n.className?.toString()
                val ci = perClass.getOrDefault(cls.orEmpty(), 0)
                perClass[cls.orEmpty()] = ci + 1
                n.getBoundsInScreen(r)
                ScreenNode(
                    index = i,
                    viewId = n.viewIdResourceName,
                    text = n.text?.toString(),
                    description = n.contentDescription?.toString(),
                    className = cls,
                    classIndex = ci,
                    clickable = n.isClickable,
                    editable = n.isEditable,
                    left = r.left / w, top = r.top / h, right = r.right / w, bottom = r.bottom / h
                )
            }
        } ?: return null
        return NodeSnapshot(pkg, nodes)
    }

    /** Re-finds [ref] in the live tree; null when the screen changed under us. */
    private suspend fun liveNode(root: AccessibilityNodeInfo, ref: NodeRef, max: Int = 500): AccessibilityNodeInfo? {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1).toFloat()
        val h = dm.heightPixels.coerceAtLeast(1).toFloat()
        return onMain {
            val n = LiveTree.collect(root, max).getOrNull(ref.index) ?: return@onMain null
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            val ok = ref.matches(
                n.viewIdResourceName, n.className?.toString(), n.text?.toString(), n.contentDescription?.toString(),
                r.exactCenterX() / w, r.exactCenterY() / h
            )
            if (ok) n else null
        }
    }

    /**
     * Taps the node [ref] (from [nodeSnapshot]). Same guards as [click]: the
     * SensitiveAppGuard and the branch-M risk gate on the resolved element.
     */
    suspend fun clickNodeRef(ref: NodeRef, approval: RiskApproval? = null): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val node = liveNode(root, ref) ?: return UiActionResult.fail("The screen changed before the tap.")
        val facts = factsOf(node)
        (RiskyElementGate.decide(facts, approval) as? RiskyElementGate.Decision.Refuse)?.let {
            return UiActionResult.risky(it, verb = "playbook_run")
        }
        val ok = gestures.clickNode(node)
        reportStep(root, node, UiStep.Op.TAP, RiskyElementGate.displayLabel(facts).ifEmpty { null }, ok)
        return if (ok) UiActionResult.ok("Tapped.") else UiActionResult.fail("Tap failed.")
    }

    /** Types [text] into the node [ref]; the text is never reported or logged. */
    suspend fun typeIntoNodeRef(ref: NodeRef, text: String): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val node = liveNode(root, ref) ?: return UiActionResult.fail("The screen changed before typing.")
        val ok = textInput.setText(node, text)
        reportStep(root, node, UiStep.Op.TYPE, null, ok)
        return if (ok) UiActionResult.ok("Typed.") else UiActionResult.fail("Typing failed.")
    }

    /** Runs [block] on the main thread; null when the node access failed. */
    suspend fun <T> onMain(block: () -> T): T? = MainThread.node(block)

    // ── Screen reading ──────────────────────────────────────────────────────

    suspend fun readScreen(): UiScreenSnapshot = screenAnalyzer.read(this)

    // ── Element actions ─────────────────────────────────────────────────────

    /** Taps the element matching [selector], scrolling to it first if needed. */
    suspend fun click(selector: UiSelector, maxScrolls: Int = 6, approval: RiskApproval? = null): UiActionResult {
        val settings = settingsStore.current()
        val root = onMain { service?.rootInActiveWindow }
        if (root == null) return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        var node = onMain { LiveTree.find(root, selector) }
        var scrolls = 0
        while (node == null && settings.autoScrollIntoView && scrolls < maxScrolls) {
            val scrolled = onMain {
                LiveTree.findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            } ?: false
            if (!scrolled) break
            scrolls++
            node = onMain { LiveTree.find(root, selector) }
        }
        if (node == null) {
            return UiActionResult.fail("Element '${selector.summary}' was not found on this screen.")
        }
        val visible = onMain { node.isVisibleToUser } ?: false
        if (!visible) return UiActionResult.fail("Element '${selector.summary}' is not visible.")
        // Branch M (gate B1): decide on the element actually found, not on the query.
        val facts = factsOf(node)
        (RiskyElementGate.decide(facts, approval) as? RiskyElementGate.Decision.Refuse)?.let {
            return UiActionResult.risky(it)
        }
        val ok = gestures.clickNode(node)
        // Branch L (App Atlas): report the step - the ELEMENT's own label (branch M), not the query.
        reportStep(root, node, UiStep.Op.TAP, RiskyElementGate.displayLabel(facts).ifEmpty { null }, ok)
        val msg = if (ok) "Tapped ${selector.summary}." else "Tapping ${selector.summary} failed."
        return if (ok) UiActionResult.ok(msg) else UiActionResult.fail(msg)
    }

    /** Double-taps the element matching [selector] (e.g. Maps zoom). */
    suspend fun doubleTap(selector: UiSelector, approval: RiskApproval? = null): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        riskGate(node, approval)?.let { return it }
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.doubleTap(bounds)
        return if (ok) UiActionResult.ok("Double-tapped ${selector.summary}.") else UiActionResult.fail("Double-tap failed.")
    }

    /**
     * Drags the element matching [selector] by ([dx], [dy]) screen points
     * (sliders, reorder handles, maps panning).
     */
    suspend fun drag(selector: UiSelector, dx: Float, dy: Float, approval: RiskApproval? = null): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        riskGate(node, approval)?.let { return it }
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.drag(
            bounds.exactCenterX(), bounds.exactCenterY(),
            bounds.exactCenterX() + dx, bounds.exactCenterY() + dy
        )
        return if (ok) UiActionResult.ok("Dragged ${selector.summary}.") else UiActionResult.fail("Drag failed.")
    }

    /** Two-finger pinch around the screen center. */
    suspend fun pinch(zoomIn: Boolean): UiActionResult {
        foregroundBlockedReason()?.let { return UiActionResult.blocked(it) }
        val dm = context.resources.displayMetrics
        val ok = gestures.pinch(dm.widthPixels / 2f, dm.heightPixels / 2f, zoomIn)
        return if (ok) {
            UiActionResult.ok(if (zoomIn) "Pinched in." else "Pinched out.")
        } else {
            UiActionResult.fail("Pinch failed.")
        }
    }

    /** Long-presses the element matching [selector]. */
    suspend fun longClick(selector: UiSelector, approval: RiskApproval? = null): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val node = onMain { LiveTree.find(root, selector) }
            ?: return UiActionResult.fail("Element '${selector.summary}' was not found.")
        riskGate(node, approval)?.let { return it }
        val bounds = android.graphics.Rect()
        onMain { node.getBoundsInScreen(bounds) }
        val ok = gestures.longPress(bounds)
        return if (ok) UiActionResult.ok("Long-pressed ${selector.summary}.") else UiActionResult.fail("Long-press failed.")
    }

    /** Types [text] into the focused field, or the field matching [into]. */
    suspend fun type(text: String, into: String?): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val field = onMain {
            if (into != null) {
                LiveTree.find(root, UiSelector(textContains = into, type = UiElementType.TEXT_FIELD))
            } else {
                LiveTree.findFocused(root) ?: LiveTree.findFirstEditable(root)
            }
        }
        if (field == null) {
            val what = into?.let { " a field matching '$it'" } ?: ""
            return UiActionResult.fail("No text field$what is available on this screen.")
        }
        val ok = textInput.setText(field, text)
        // Branch L (App Atlas): field identity only — the typed text is never reported.
        reportStep(root, field, UiStep.Op.TYPE, into, ok)
        return if (ok) {
            UiActionResult.ok("Typed into the field${into?.let { " ($it)" } ?: ""}.")
        } else {
            UiActionResult.fail("Typing failed — the field may not accept text.")
        }
    }

    // ── Scrolling & gestures ────────────────────────────────────────────────

    suspend fun scroll(direction: String): UiActionResult {
        val root = onMain { service?.rootInActiveWindow } ?: return UiActionResult.fail(notConnected())
        guardReason(onMain { root.packageName })?.let { return UiActionResult.blocked(it) }
        val scrollable = onMain { LiveTree.findScrollable(root) }
        val ok = when (direction) {
            "up" -> scrollable?.let { gestures.scrollBackward(it) } ?: swipeGesture("down")
            "down" -> scrollable?.let { gestures.scrollForward(it) } ?: swipeGesture("up")
            else -> swipeGesture(direction)
        }
        UiStepJournal.step(UiStep(onMain { root.packageName?.toString() }.orEmpty(), UiStep.Op.SCROLL, null, direction, ok))
        return if (ok) UiActionResult.ok("Scrolled $direction.") else UiActionResult.fail("Could not scroll $direction.")
    }

    suspend fun swipe(direction: String): UiActionResult {
        // A swipe is a raw gesture on the foreground app (a slider in the Tesla
        // app, a swipe-to-reply in WhatsApp): same guard as taps.
        foregroundBlockedReason()?.let { return UiActionResult.blocked(it) }
        // Branch M: a raw swipe must not complete a "slide to pay / confirm" control.
        slideToConfirmOnScreen()?.let { label ->
            return UiActionResult.blocked(
                "A slide-to-confirm control ('${label.take(80)}') is on screen - a swipe could pay / confirm. Let the user swipe it."
            )
        }
        val ok = swipeGesture(direction)
        return if (ok) UiActionResult.ok("Swiped $direction.") else UiActionResult.fail("Swipe $direction failed.")
    }

    /** Action-guard refusal for [pkg]; user-added banking packages come from [SensitiveAppStore]. */
    private suspend fun guardReason(pkg: CharSequence?): String? =
        SensitiveAppGuard.blockedReason(pkg, sensitiveApps.current())

    /**
     * Screen-capture refusal (screenshot / OCR / scan_qr) or null (K).
     * Fail-closed: refuses when the service is off or the active window is
     * unknown, when the active or last tracked app is protected, and when any
     * visible application window (split screen, PiP, floating) is protected.
     */
    suspend fun screenReadBlockedReason(): String? {
        val s = service ?: return notConnected()
        val activePkg = onMain { s.rootInActiveWindow?.packageName?.toString() }
        val visible = onMain {
            s.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { w -> w.root?.packageName?.toString() }
        } ?: listOf(null)
        return SensitiveAppGuard.screenCaptureBlockedReason(
            activePkg, uiState.currentPackage.value, visible, sensitiveApps.current()
        )
    }

    /** The user's extra protected packages (for the screen analyzer). */
    internal suspend fun sensitiveExtras(): Set<String> = sensitiveApps.current()

    /** Branch M: identity of [node] (+ its nearest clickable ancestor when the node itself is not clickable). */
    private suspend fun factsOf(node: AccessibilityNodeInfo): RiskyElementGate.Facts = onMain {
        val ancestors = mutableListOf<RiskyElementGate.Facts>()
        if (!node.isClickable) {
            var p = node.parent
            var depth = 0
            while (p != null && depth < 6) {
                if (p.isClickable) {
                    ancestors += RiskyElementGate.Facts(p.text?.toString(), p.contentDescription?.toString(), p.viewIdResourceName)
                    break
                }
                p = p.parent
                depth++
            }
        }
        RiskyElementGate.Facts(node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName, ancestors)
    } ?: RiskyElementGate.Facts()

    /** Branch M: refusal when the resolved element is risky and not approved for exactly this element. */
    private suspend fun riskGate(node: AccessibilityNodeInfo, approval: RiskApproval?): UiActionResult? =
        (RiskyElementGate.decide(factsOf(node), approval) as? RiskyElementGate.Decision.Refuse)
            ?.let { UiActionResult.risky(it, verb = "ui_gesture") }

    /** Branch M: label of a visible slide / swipe-to-confirm control, or null. */
    private suspend fun slideToConfirmOnScreen(): String? = onMain {
        val root = service?.rootInActiveWindow ?: return@onMain null
        LiveTree.collect(root).firstNotNullOfOrNull { n ->
            if (!n.isVisibleToUser) return@firstNotNullOfOrNull null
            val label = LiveTree.labelOf(n)
            val seek = n.className?.toString()?.contains("SeekBar", ignoreCase = true) == true
            label.takeIf {
                it.isNotEmpty() &&
                    (SafetyClassifier.isSlideToConfirm(it) || (seek && SafetyClassifier.isRiskyTarget(it)))
            }
        }
    }

    /** Branch L: tells the App Atlas recorder what was done (best effort, never throws). */
    private suspend fun reportStep(root: AccessibilityNodeInfo, node: AccessibilityNodeInfo, op: UiStep.Op, label: String?, ok: Boolean) {
        runCatching {
            val pkg = onMain { root.packageName?.toString() }.orEmpty()
            val rid = onMain { node.viewIdResourceName }
            UiStepJournal.step(UiStep(pkg, op, rid, label, ok))
        }
    }

    /** Guard refusal for the current foreground app, or null (also when unknown). */
    private suspend fun foregroundBlockedReason(): String? {
        val root = onMain { service?.rootInActiveWindow } ?: return null
        return guardReason(onMain { root.packageName })
    }

    private suspend fun swipeGesture(direction: String): Boolean {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val (fx, fy, dx, dy) = when (direction) {
            "up" -> arrayOf(w / 2, h * 0.75f, 0f, -h * 0.45f)
            "down" -> arrayOf(w / 2, h * 0.25f, 0f, h * 0.45f)
            "left" -> arrayOf(w * 0.8f, h / 2, -w * 0.45f, 0f)
            "right" -> arrayOf(w * 0.2f, h / 2, w * 0.45f, 0f)
            else -> return false
        }
        return gestures.swipe(fx, fy, dx, dy)
    }

    // ── System navigation ───────────────────────────────────────────────────

    suspend fun navigate(action: String): UiActionResult {
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "split_screen" -> {
                if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                else return UiActionResult.fail("Split screen is not supported on this Android version.")
            }
            else -> return UiActionResult.fail("Unknown navigation action '$action'.")
        }
        val ok = gestures.globalAction(code)
        return if (ok) UiActionResult.ok("Performed $action.") else UiActionResult.fail("Could not perform $action.")
    }

    // ── App launching ───────────────────────────────────────────────────────

    /** Launches an installed app by its visible label ("open WhatsApp"). */
    suspend fun launchApp(label: String): UiActionResult {
        val pkg = resolveAppPackage(label)
            ?: return UiActionResult.fail("No installed app matches '$label'.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return UiActionResult.fail("App '$label' ($pkg) has no launcher activity.")
        val launched = ToolIntents.launch(context, intent)
        return if (launched) {
            UiActionResult.ok("Opened $label.")
        } else {
            UiActionResult.fail("Could not open $label.")
        }
    }

    private fun resolveAppPackage(label: String): String? {
        val query = label.trim()
        if (query.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getInstalledApplications(0).firstOrNull { app ->
                pm.getApplicationLabel(app).toString()
                    .contains(query, ignoreCase = true)
            }?.packageName
        }.getOrNull()
    }

    // ── Vision support (screenshot + OCR) ───────────────────────────────────

    /** Captures the screen as a bitmap (API 30+; needs the service connected). */
    suspend fun captureScreenshot(): Bitmap? {
        val s = service ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        // K: never capture a banking/payment, Tesla or WhatsApp screen (any visible window).
        if (screenReadBlockedReason() != null) return null
        // takeScreenshot must be invoked from the main thread; the result
        // arrives on the main executor, so both sides stay on main. A timeout
        // guarantees the coroutine can never hang on a rejected capture.
        return withTimeoutOrNull(5_000L) { suspendCancellableCoroutine { cont ->
            MainThread.post {
                runCatching {
                    val cb = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val bmp = screenshot.hardwareBuffer?.let { Bitmap.wrapHardwareBuffer(it, null) }
                            cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.w("AccessibilityController: screenshot failed code=$errorCode")
                            cont.resume(null)
                        }
                    }
                    s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, context.mainExecutor, cb)
                }.onFailure {
                    Timber.w(it, "AccessibilityController: takeScreenshot rejected")
                    cont.resume(null)
                }
            }
        }
        }
    }

    // ── Status helpers ──────────────────────────────────────────────────────

    fun openSystemSettings(): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return ToolIntents.launch(context, intent)
    }

    private fun notConnected(): String =
        "The accessibility service is not enabled. Open Settings → Accessibility → AndroLLM UI Automation and turn it on, then try again."
}
