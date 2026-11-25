package io.androllm.core.accessibility.controller

/**
 * Branch N (app playbooks): a light, screenshot-free view of the live
 * accessibility tree used by the playbook replay engine to check expected
 * screens and score element locators. Text fields stay in memory only — the
 * engine reports view ids, never screen text.
 */
data class ScreenNode(
    /** Pre-order position in the live tree (LiveTree.collect order). */
    val index: Int,
    /** Full view id ("com.app:id/search_button") or null. */
    val viewId: String?,
    val text: String?,
    val description: String?,
    /** Fully-qualified class name. */
    val className: String?,
    /** Index of this node among visible nodes of the same class (class+index locator). */
    val classIndex: Int,
    val clickable: Boolean,
    val editable: Boolean,
    /** Bounds relative to the screen (0..1): left, top, right, bottom. */
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    /** View id entry name ("search_button"). */
    val idEntry: String? get() = viewId?.substringAfter(":id/")?.takeIf { it.isNotEmpty() }
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Visible nodes of the active window. [blockedReason] set (and no nodes) for protected apps. */
data class NodeSnapshot(
    val packageName: String?,
    val nodes: List<ScreenNode>,
    val blockedReason: String? = null
)

/** Identifies one node of a [NodeSnapshot] for a later action; re-verified against the live tree. */
data class NodeRef(val index: Int, val viewId: String?, val className: String?)
