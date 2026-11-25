package io.androllm.core.appatlas.playbook

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.search.AtlasSearch
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import io.androllm.core.tools.tool.impl.ToolArgs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Lookup helpers shared by the tools, the atlas search and the fast path. */
@Singleton
class PlaybookCatalog @Inject constructor(
    private val repository: PlaybookRepository,
    private val atlas: AppAtlasRepository
) {
    /** Compact lines per package (for app_atlas_search), max [perApp] each. */
    suspend fun linesFor(packages: List<String>, perApp: Int = 3): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (pkg in packages) {
            val entry = atlas.cachedEntry(pkg)
            if (entry?.sensitive == true) continue
            val list = repository.forPackage(pkg, entry?.label)
            if (list.isEmpty()) continue
            out[pkg] = list.sortedByDescending { it.successCount }.take(perApp).map { PlaybookFormat.line(it, entry?.versionCode) }
        }
        return out
    }

    /** playbook_list: by package name, or by query (atlas app match + name / description words). */
    suspend fun find(query: String): List<Pair<StoredPlaybook, Long?>> {
        val q = query.trim()
        val all = repository.all()
        val pkgs = if (all.any { it.playbook.packageName == q }) listOf(q)
        else runCatching { atlas.search(q).map { it.entry.packageName } }.getOrDefault(emptyList())
        val words = AtlasSearch.normalize(q).split(' ').filter { it.length >= 3 }
        val byWord = all.filter { sp ->
            val hay = AtlasSearch.normalize("${sp.playbook.name} ${sp.playbook.description} ${sp.playbook.appLabel.orEmpty()}")
            words.any { it in hay }
        }.map { it.playbook.packageName }
        val result = mutableListOf<Pair<StoredPlaybook, Long?>>()
        for (pkg in (pkgs + byWord).distinct()) {
            val entry = atlas.cachedEntry(pkg)
            repository.forPackage(pkg, entry?.label).forEach { result += it to entry?.versionCode }
        }
        return result
    }

    /** Playbooks the zero-LLM router may pick: installed, not protected, status OK. */
    suspend fun routable(): List<StoredPlaybook> {
        val extras = repository.extras()
        return repository.all().filter { sp ->
            val e = atlas.cachedEntry(sp.playbook.packageName) ?: return@filter false
            !e.sensitive && sp.playbook.triggers.isNotEmpty() &&
                io.androllm.core.appatlas.index.SensitiveClassifier.reason(e.packageName, e.label, extras) == null &&
                PlaybookPolicy.effectiveStatus(sp, e.versionCode) == PlaybookStatus.OK
        }
    }
}

/** `playbook_list` — which ready-made app actions exist (names, params, one-line descriptions). */
@Singleton
class PlaybookListTool @Inject constructor(private val catalog: PlaybookCatalog) : Tool {

    override val spec = ToolSpec(
        name = NAME,
        description = "List ready-made app actions (playbooks) for an app or task. Run one with playbook_run.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "App name, package or task, e.g. 'youtube'")
                }
            }
            putJsonArray("required") { add("query") }
        },
        permission = ToolPermission.APPS,
        category = ToolCategory.MEDIA,
        supportedTasks = listOf("app", "control app", "in the app", "app action", "playbook"),
        estimatedLatencyMs = 50L,
        capabilities = listOf("app_atlas", "playbooks")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val q = ToolArgs.str(arguments, "query", "package", "app", "q")?.take(120)
            ?: return ToolResult.Failure("Missing required argument: query")
        val found = catalog.find(q)
        if (found.isEmpty()) return ToolResult.Failure("No playbooks for \"${q.take(60)}\". Use app_atlas_search.", retryable = false)
        val sb = StringBuilder()
        for ((sp, v) in found.take(MAX_LINES)) {
            val line = PlaybookFormat.line(sp, v)
            if (sb.length + line.length > MAX_CHARS) break
            sb.append(line).append('\n')
        }
        return ToolResult.Success(
            summary = sb.toString().trimEnd(),
            data = buildJsonObject { putJsonArray("ids") { found.take(MAX_LINES).forEach { add(it.first.playbook.id) } } }
        )
    }

    companion object {
        const val NAME = "playbook_list"
        const val MAX_LINES = 12
        const val MAX_CHARS = 900
    }
}

/**
 * `playbook_run` — replays a playbook deterministically (no model call per
 * step). Risky steps ask the owner on a tap-only card at that step (resolved
 * element). On a screen mismatch it falls back to ui_run when `fallback` is
 * true (default).
 */
@Singleton
class PlaybookRunTool @Inject constructor(private val runner: PlaybookRunner) : Tool {

    override val spec = ToolSpec(
        name = NAME,
        description = "Run a ready-made app action by id with its args, e.g. id='com.google.android.youtube/search', args={\"query\":\"cats\"}.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("id") {
                    put("type", "string")
                    put("description", "Playbook id from playbook_list / app_atlas_search")
                }
                putJsonObject("args") {
                    put("type", "object")
                    put("description", "Values for the playbook params")
                }
                putJsonObject("fallback") {
                    put("type", "boolean")
                    put("description", "On failure try ui_run (default true)")
                }
            }
            putJsonArray("required") { add("id") }
        },
        permission = ToolPermission.ACCESSIBILITY,
        category = ToolCategory.DEVICE,
        supportedTasks = listOf("app", "control app", "in the app", "app action", "playbook"),
        estimatedLatencyMs = 2_000L,
        // Replay is fast; a fallback ui_run needs the ui_run budget.
        executionTimeoutMs = 180_000L,
        capabilities = listOf("app_atlas", "playbooks")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val id = ToolArgs.str(arguments, "id", "playbook", "name")?.trim()
            ?: return ToolResult.Failure("Missing required argument: id")
        return runner.run(id, argsOf(arguments), ToolArgs.bool(arguments, "fallback", default = true))
    }

    companion object {
        const val NAME = "playbook_run"

        /** args object (or flat top-level keys as a small-model fallback) → string map. */
        internal fun argsOf(arguments: JsonObject): Map<String, String?> {
            val nested = (arguments["args"] as? JsonObject)
                ?: (arguments["args"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                }
            val src = nested ?: JsonObject(arguments.filterKeys { it !in setOf("id", "playbook", "name", "fallback") })
            return src.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
        }
    }
}

/**
 * Branch N: Jarvis link data tasks.
 * - `playbooks.sync`  body = a playbook bundle (validated strictly; protected apps never imported);
 * - `playbooks.relearn` body = {"limit"?: n} → JSON list of pending relearn requests (ids only).
 */
@Singleton
class PlaybookSyncHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlaybookRepository,
    private val atlas: AppAtlasRepository
) : RemoteDataTaskHandler {

    override val types: Set<String> = setOf(TYPE_SYNC, TYPE_RELEARN)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult = when (type) {
        TYPE_SYNC -> {
            if (body == null) RemoteDataResult(false, "playbooks.sync needs a bundle body")
            else {
                val report = repository.importJson(body.toString(), SOURCE_JARVIS, context.packageName) { atlas.cachedEntry(it)?.label }
                RemoteDataResult(report.accepted.isNotEmpty() || report.rejected.isEmpty(), report.summary())
            }
        }
        TYPE_RELEARN -> {
            val limit = (body?.get("limit") as? JsonPrimitive)?.intOrNull ?: 50
            val list = repository.pullRelearn(limit)
            RemoteDataResult(true, Json.encodeToString(ListSerializer(RelearnRequest.serializer()), list))
        }
        else -> RemoteDataResult(false, "unknown task type")
    }

    companion object {
        const val TYPE_SYNC = "playbooks.sync"
        const val TYPE_RELEARN = "playbooks.relearn"
        const val SOURCE_JARVIS = "jarvis"
    }
}

/** Branch N: imports a bundle from a local file / content URI (Settings → App Atlas → Import playbooks). */
@Singleton
class PlaybookFileImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlaybookRepository,
    private val atlas: AppAtlasRepository
) {
    suspend fun import(uri: Uri): ImportReport = withContext(Dispatchers.IO) {
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(PlaybookValidator.MAX_BUNDLE_CHARS * 3 + 1)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n >= buf.size) null else String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull() ?: return@withContext ImportReport(emptyList(), listOf(ImportReport.Rejection("file", "unreadable or too large")))
        repository.importJson(raw, "file", context.packageName) { atlas.cachedEntry(it)?.label }
    }
}

/**
 * Branch N: zero-LLM fast path. [match] is pure routing over the routable
 * playbooks; [run] executes through the same runner (confirmation cards,
 * guards). Not yet wired into the chat / voice pipeline (see N-result.md).
 */
@Singleton
class PlaybookFastPath @Inject constructor(
    private val catalog: PlaybookCatalog,
    private val runner: PlaybookRunner,
    private val repository: PlaybookRepository
) {
    suspend fun match(utterance: String): RouteMatch? = PlaybookRouter.match(utterance, catalog.routable().map { it.playbook })

    /** No ui_run fallback here: on failure the caller hands the utterance to the model as usual. */
    suspend fun run(m: RouteMatch): ToolResult {
        val sp = repository.get(m.playbook.id) ?: return ToolResult.Failure("playbook gone", retryable = false)
        return runner.runResolved(sp, m.slots, allowFallback = false)
    }
}
