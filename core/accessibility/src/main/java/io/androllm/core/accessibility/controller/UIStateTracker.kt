package io.androllm.core.accessibility.controller

import android.view.accessibility.AccessibilityEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the ongoing UI context the assistant should always know: current
 * package/activity, focused and selected text, loading state, and a small
 * navigation stack (package history). Driven by accessibility events — no
 * polling, so CPU stays flat while the engine idles.
 */
@Singleton
class UIStateTracker @Inject constructor() {

    private val _currentPackage = MutableStateFlow("")
    val currentPackage: StateFlow<String> = _currentPackage.asStateFlow()

    private val _windowTitle = MutableStateFlow("")
    val windowTitle: StateFlow<String> = _windowTitle.asStateFlow()

    private val _focusedText = MutableStateFlow("")
    val focusedText: StateFlow<String> = _focusedText.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    /** True while a progress indicator is visible (screen is busy). */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Most recent packages, newest first — the assistant's "navigation stack". */
    private val _navStack = MutableStateFlow<List<String>>(emptyList())
    val navStack: StateFlow<List<String>> = _navStack.asStateFlow()

    private val MAX_STACK = 8

    /** Package the focused/selected text belongs to ("" = none). */
    @Volatile private var textPackage: String = ""

    fun onEvent(event: AccessibilityEvent) {
        onEvent(
            pkg = event.packageName?.toString().orEmpty(),
            eventType = event.eventType,
            className = event.className?.toString(),
            firstText = event.text.firstOrNull()
        )
    }

    /** Event core, plain values only (unit-testable). */
    internal fun onEvent(pkg: String, eventType: Int, className: String?, firstText: CharSequence?) {
        if (pkg.isNotBlank() && pkg != _currentPackage.value) {
            _currentPackage.value = pkg
            _navStack.value = (listOf(pkg) + _navStack.value.filter { it != pkg }).take(MAX_STACK)
            // K: text typed/focused in the previous app (a bank amount, a card
            // number) must never follow the user into the next app's snapshot.
            clearText()
        }

        // Window title = the activity label when available, else package.
        val title = className?.substringAfterLast('.') ?: ""
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                _windowTitle.value = title
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                firstText?.let {
                    _focusedText.value = it.toString().take(80)
                    textPackage = pkg.ifBlank { _currentPackage.value }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit // freshness signal only
        }
    }

    private fun clearText() {
        _focusedText.value = ""
        _selectedText.value = ""
        textPackage = ""
    }

    /** Called by the screen analyzer after a tree read (loading detection). */
    fun setLoading(loading: Boolean) {
        _loading.value = loading
    }

    fun setFocusedText(text: String) {
        _focusedText.value = text.take(80)
        textPackage = _currentPackage.value
    }

    fun setSelectedText(text: String) {
        _selectedText.value = text.take(80)
        textPackage = _currentPackage.value
    }

    fun snapshot(): UiContextSnapshot = UiContextSnapshot(
        currentPackage = _currentPackage.value,
        windowTitle = _windowTitle.value,
        focusedText = _focusedText.value,
        selectedText = _selectedText.value,
        textPackage = textPackage,
        loading = _loading.value,
        navStack = _navStack.value
    )
}

/** Immutable context snapshot fed to the screen analyzer / debug UI. */
data class UiContextSnapshot(
    val currentPackage: String = "",
    val windowTitle: String = "",
    val focusedText: String = "",
    val selectedText: String = "",
    /** Package [focusedText]/[selectedText] came from; only valid for that app. */
    val textPackage: String = "",
    val loading: Boolean = false,
    val navStack: List<String> = emptyList()
)
