package io.androllm.core.tools.uistep

import java.util.concurrent.CopyOnWriteArraySet

/** One UI action the accessibility engine performed in another app. */
data class UiStep(
    val packageName: String,
    val op: Op,
    /** Full view resource id ("pkg:id/search_button"), when the view had one. */
    val resourceId: String?,
    /**
     * The label the action targeted (ui_click label, ui_type field hint, or the
     * scroll direction). NEVER typed text. Listeners must still treat it as
     * untrusted: it can be screen content (a contact name).
     */
    val label: String?,
    val success: Boolean
) {
    enum class Op { TAP, TYPE, SCROLL }
}

/**
 * Process-wide, side-effect-free journal of UI steps (branch L, App Atlas).
 * The accessibility controller reports what it did; the atlas' learned-path
 * recorder listens. Listener failures never affect the UI action.
 */
object UiStepJournal {

    interface Listener {
        fun onStep(step: UiStep) {}
        /** A ui_run task finished; [goal] is the model's task text (user words — derive concepts only). */
        fun onTaskFinished(goal: String, success: Boolean) {}
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(listener: Listener) { listeners += listener }

    fun unregister(listener: Listener) { listeners -= listener }

    fun step(step: UiStep) {
        if (step.packageName.isBlank()) return
        listeners.forEach { runCatching { it.onStep(step) } }
    }

    fun taskFinished(goal: String, success: Boolean) {
        listeners.forEach { runCatching { it.onTaskFinished(goal, success) } }
    }
}
