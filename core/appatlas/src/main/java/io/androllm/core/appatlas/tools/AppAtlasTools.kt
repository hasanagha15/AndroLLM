package io.androllm.core.appatlas.tools

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.appatlas.learn.LearnedPathPolicy
import io.androllm.core.appatlas.learn.LearnedPathRecorder
import io.androllm.core.appatlas.model.ActionKind
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.AtlasAction
import io.androllm.core.appatlas.search.AtlasSearch
import io.androllm.core.appatlas.search.Concepts
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import io.androllm.core.tools.tool.impl.ToolIntents
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

/**
 * `app_atlas_search` — answers "how do I do X in an app" from the on-device
 * atlas BEFORE any screen reading: top apps, their deep links / shortcuts /
 * in-app search, UI ids and learned element paths. Read-only.
 */
@Singleton
class AppAtlasSearchTool @Inject constructor(
    private val repository: AppAtlasRepository,
    private val recorder: LearnedPathRecorder
) : Tool {

    init {
        // Branch M (N3): the "call app_open_action…" header is app-authored — keep it outside the untrusted frame.
        io.androllm.core.tools.api.ToolAppNotes.register(NAME, AtlasSearch.HEADER)
    }

    override val spec = ToolSpec(
        name = NAME,
        description = "Look up installed apps in the on-device App Atlas: how to open a screen directly (deep links, shortcuts, in-app search), UI element ids and learned tap paths. Call it first for any request to do something in an app, then app_open_action.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "App name and/or what to do, e.g. 'youtube search', 'telegram new message'")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA,
        supportedTasks = listOf("open app", "app", "control app", "in the app", "deep link", "shortcut", "app action"),
        estimatedLatencyMs = 150L,
        capabilities = listOf("app_atlas")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = ToolArgs.str(arguments, "query", "app", "q", "task")?.take(200)
            ?: return ToolResult.Failure("Missing required argument: query")
        if (!repository.isEnabled) {
            return ToolResult.Failure("The App Atlas is turned off in Settings. Use launch_app.", retryable = false)
        }
        val entries = repository.entries()
        if (entries.isEmpty()) {
            repository.requestSync(AppAtlasRepository.Mode.INCREMENTAL)
            return ToolResult.Failure(
                "The App Atlas is still being built (first run). Use launch_app for now.",
                retryable = false
            )
        }
        recorder.noteQuery(query)
        val hits = repository.search(query)
        Timber.d("AppAtlas: search → %d hit(s)", hits.size)
        val text = AtlasSearch.render(hits, query)
        return if (hits.isEmpty()) {
            ToolResult.Failure(text, retryable = false)
        } else {
            ToolResult.Success(
                summary = text,
                data = buildJsonObject {
                    putJsonArray("packages") { hits.forEach { add(it.entry.packageName) } }
                }
            )
        }
    }

    companion object { const val NAME = "app_atlas_search" }
}

/**
 * `app_open_action` — fires one atlas action (launcher, deep link, static
 * shortcut, in-app search). Same risk class as launch_app: it only OPENS a
 * screen, never taps, types, sends or pays. Sensitive apps allow only "open".
 * Remote-origin calls go through the executor's RemoteToolPolicy like every
 * tool (confirmation card).
 */
@Singleton
class AppOpenActionTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppAtlasRepository,
    private val recorder: LearnedPathRecorder
) : Tool {

    override val spec = ToolSpec(
        name = NAME,
        description = "Open an app screen directly using an action from app_atlas_search (no tapping): action_id 'open', 'search' (with query), 'shortcut:…' or 'link:N' (optionally a filled-in uri with the same scheme and host).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("package") {
                    put("type", "string")
                    put("description", "Package name from app_atlas_search")
                }
                putJsonObject("action_id") {
                    put("type", "string")
                    put("description", "Action id from app_atlas_search, e.g. 'open', 'search', 'link:2', 'shortcut:compose'")
                }
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "Optional: filled-in deep link for a link:N action (same scheme and host)")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Optional: search text for the 'search' action")
                }
            }
            putJsonArray("required") { add("package"); add("action_id") }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA,
        supportedTasks = listOf("open app", "app", "deep link", "shortcut", "app action"),
        estimatedLatencyMs = 500L,
        capabilities = listOf("app_atlas")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val pkg = ToolArgs.str(arguments, "package", "pkg", "app")?.trim()
            ?: return ToolResult.Failure("Missing required argument: package")
        val actionId = ToolArgs.str(arguments, "action_id", "action", "id")?.trim() ?: "open"
        val uri = ToolArgs.str(arguments, "uri", "url", "link")
        val query = ToolArgs.str(arguments, "query", "text", "q")
        if (!repository.isEnabled) {
            return ToolResult.Failure("The App Atlas is turned off in Settings. Use launch_app.", retryable = false)
        }
        if (pkg == context.packageName) {
            return ToolResult.Failure("This assistant cannot open itself through the atlas.", retryable = false)
        }
        val extras = repository.currentSensitiveExtras()
        val entry = repository.entry(pkg)?.let { SensitiveClassifier.effective(it, extras) }
            ?: return ToolResult.Failure(
                "\"$pkg\" is not in the App Atlas (not installed, not launchable, or excluded in Settings). Call app_atlas_search first.",
                retryable = false
            )
        val action = entry.action(actionId)
            ?: return ToolResult.Failure(
                "${entry.label} has no action '$actionId'. Available: ${entry.actions.joinToString(", ") { it.id }.take(300)}.",
                retryable = false
            )

        val uriToUse = when (val v = ActionGuard.check(entry, action, uri, query, extras)) {
            is ActionGuard.Verdict.Refused -> return ToolResult.Failure(v.reason, retryable = false)
            is ActionGuard.Verdict.Allowed -> v.uri
        }

        if (action.kind == ActionKind.DEEP_LINK && uriToUse != null) {
            val u = runCatching { Uri.parse(uriToUse) }.getOrNull()
            if (u == null || !ActionGuard.launchParseMatches(action, u.scheme, u.host, u.userInfo)) {
                return ToolResult.Failure("uri was rejected: it must keep the scheme and host of '${action.id}'.", retryable = false)
            }
        }
        val intent = buildIntent(entry, action, uriToUse, query)
            ?: return ToolResult.Failure("Could not build an intent for '${action.id}' of ${entry.label}.", retryable = false)
        val resolvable = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        if (!resolvable) {
            return ToolResult.Failure(
                "${entry.label} no longer handles '${action.id}' (the app may have updated). Use action_id=open, then ui_read_screen.",
                data = buildJsonObject { put("status", "not_resolvable"); put("package", pkg) }
            )
        }
        if (!ToolIntents.launch(context, intent)) {
            return ToolResult.Failure(
                "The system blocked opening ${entry.label} (${action.id}). Try launch_app.",
                data = buildJsonObject { put("status", "launch_blocked"); put("package", pkg) }
            )
        }
        recorder.beginSession(pkg, entry.versionCode, query ?: action.title)

        val hint = learnedHint(entry, action, query)
        val summary = buildString {
            append("Opened ").append(entry.label).append(" → ").append(AtlasSearch.describe(action)).append('.')
            if (entry.sensitive) append(" Protected app: do not tap or type there; the user continues.")
            else if (hint != null) append(" Learned path: ").append(hint)
        }
        return ToolResult.Success(
            summary = summary,
            data = buildJsonObject {
                put("status", "opened")
                put("package", pkg)
                put("action_id", action.id)
            }
        )
    }

    private suspend fun learnedHint(entry: AppAtlasEntry, action: AtlasAction, query: String?): String? {
        if (entry.sensitive) return null
        val concepts = Concepts.of(listOfNotNull(query, action.title, action.id).joinToString(" "))
        val path = repository.paths()[entry.packageName].orEmpty()
            .filter { p -> p.taskKey.split('+').any { it in concepts } }
            .sortedWith(compareBy({ it.stale }, { -it.successCount }))
            .firstOrNull() ?: return null
        return LearnedPathPolicy.render(path, entry.versionCode)
    }

    private fun buildIntent(entry: AppAtlasEntry, action: AtlasAction, uri: String?, query: String?): Intent? {
        val pkg = entry.packageName
        return when (action.kind) {
            ActionKind.LAUNCH ->
                context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: action.component?.let(::component)?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(it) }
            ActionKind.DEEP_LINK -> uri?.let {
                // No BROWSABLE category: it would stop matching non-browsable filters.
                Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg)
            }
            ActionKind.SEARCH -> {
                val i = Intent(Intent.ACTION_SEARCH)
                action.component?.let(::component)?.let { i.component = it } ?: i.setPackage(pkg)
                if (!query.isNullOrBlank()) i.putExtra(SearchManager.QUERY, query)
                i
            }
            ActionKind.SHORTCUT -> {
                val i = Intent(action.intentAction ?: Intent.ACTION_VIEW)
                action.data?.let { i.data = Uri.parse(it) }
                action.component?.let(::component)?.let { i.component = it } ?: i.setPackage(pkg)
                action.extras.forEach { (k, v) -> i.putExtra(k, v) }
                i
            }
        }
    }

    private fun component(flat: String): ComponentName? {
        val pkg = flat.substringBefore('/')
        val cls = flat.substringAfter('/')
        if (pkg.isEmpty() || cls.isEmpty()) return null
        return ComponentName(pkg, cls)
    }

    companion object { const val NAME = "app_open_action" }
}
