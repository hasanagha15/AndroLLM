package io.androllm.core.appatlas.model

import kotlinx.serialization.Serializable

/** How an [AtlasAction] is fired. None of them taps or types anything. */
@Serializable
enum class ActionKind {
    /** The app's launcher activity (same as launch_app). */
    LAUNCH,
    /** ACTION_VIEW on a URI the app declares (custom scheme or http(s) app link). */
    DEEP_LINK,
    /** A static shortcut declared in the app's `android.app.shortcuts` XML. */
    SHORTCUT,
    /** ACTION_SEARCH on the app's searchable activity, with a query extra. */
    SEARCH
}

/**
 * One way to jump straight to a screen of an app. [id] is stable for a given
 * app version ("open", "search", "link:2", "shortcut:compose") and is what the
 * model passes to `app_open_action`.
 */
@Serializable
data class AtlasAction(
    val id: String,
    val kind: ActionKind,
    /** Human label: shortcut label, activity label, or a description of the link. */
    val title: String,
    /** Example URI (DEEP_LINK) — the model may pass a filled-in URI with the same scheme/host. */
    val uri: String? = null,
    val scheme: String? = null,
    val host: String? = null,
    val pathPrefix: String? = null,
    /** Fully-qualified target activity ("pkg/cls"), when the action needs an explicit component. */
    val component: String? = null,
    /** Intent action for SHORTCUT intents (VIEW, MAIN, app-specific). */
    val intentAction: String? = null,
    /** Data URI of a SHORTCUT intent. */
    val data: String? = null,
    /** Extras of a SHORTCUT intent (string values only — what the XML can express). */
    val extras: Map<String, String> = emptyMap(),
    /** False when a third-party app cannot start it (non-exported / permission-protected target). */
    val fireable: Boolean = true
)

/** Everything the atlas knows about one installed, launchable app. */
@Serializable
data class AppAtlasEntry(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String? = null,
    val lastUpdateTime: Long = 0L,
    /** Banking / payment / Tesla / WhatsApp etc.: metadata only, no automation, only "open". */
    val sensitive: Boolean = false,
    val sensitiveReason: String? = null,
    /** False when the target's manifest could not be read (fallback: shortcuts + search only). */
    val manifestParsed: Boolean = false,
    val actions: List<AtlasAction> = emptyList(),
    /** Resource entry names of views ("search_button") — usable as ui_click `id`. */
    val uiIds: List<String> = emptyList(),
    /** App-defined UI strings in the device locale ("Search", "Поиск") — usable as ui_click `label`. */
    val uiStrings: List<String> = emptyList(),
    val indexedAt: Long = 0L
) {
    fun action(id: String): AtlasAction? = actions.firstOrNull { it.id == id }
}

/** Minimal package facts read cheaply for every launchable app (change detection). */
data class PackageBasics(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val lastUpdateTime: Long,
    /** Fully-qualified launcher activity class name. */
    val launcherActivity: String?,
    val isSystem: Boolean = false
)

// ── Manifest model (parsed from the target's binary AndroidManifest.xml) ──

data class DataSpec(
    val scheme: String? = null,
    val host: String? = null,
    val port: String? = null,
    val path: String? = null,
    val pathPrefix: String? = null,
    val pathPattern: String? = null,
    val mimeType: String? = null
)

data class ParsedFilter(
    val actions: Set<String>,
    val categories: Set<String>,
    val data: List<DataSpec>
)

data class ParsedComponent(
    /** Fully-qualified class name (relative ".Foo" names are resolved). */
    val name: String,
    val label: String?,
    val exported: Boolean,
    val enabled: Boolean,
    /** android:permission on the component — a third party cannot start it without that permission. */
    val permission: String?,
    val filters: List<ParsedFilter>,
    /** Raw android:resource of the `android.app.shortcuts` meta-data ("@2131951616" / "@xml/shortcuts"). */
    val shortcutsResource: String?,
    val isAlias: Boolean = false
) {
    val startableByOthers: Boolean get() = exported && enabled && permission == null
}

data class ParsedManifest(
    val packageName: String,
    val versionCode: Long?,
    val components: List<ParsedComponent>
)

// ── Static shortcuts (res/xml/shortcuts.xml of the target) ──

data class ShortcutIntent(
    val action: String?,
    val targetPackage: String?,
    val targetClass: String?,
    val data: String?,
    val extras: Map<String, String>
)

data class ParsedShortcut(
    val id: String,
    val shortLabel: String?,
    val longLabel: String?,
    val enabled: Boolean,
    /** The back stack; the LAST intent is the one that is shown. */
    val intents: List<ShortcutIntent>
)

// ── Learned element paths ──

/** One recorded UI step. Never contains typed text or screen content. */
@Serializable
data class PathStep(
    /** tap | type | scroll */
    val op: String,
    /** View resource entry name ("search_button"), when the view had one. */
    val id: String? = null,
    /** App-defined UI string (only kept when it is in the app's own vocabulary). */
    val label: String? = null,
    /** Scroll direction for op = scroll. */
    val arg: String? = null
)

@Serializable
data class LearnedPath(
    val packageName: String,
    /** Canonical action concepts of the task ("search", "send+message") — never the user's words. */
    val taskKey: String,
    val versionCode: Long,
    val steps: List<PathStep>,
    val successCount: Int = 1,
    /** True when recorded from a ui_run that reported success; false = model-driven sequence. */
    val verified: Boolean = false,
    /** True once the app updated after recording — kept as a hint only. */
    val stale: Boolean = false,
    val updatedAt: Long = 0L
)
