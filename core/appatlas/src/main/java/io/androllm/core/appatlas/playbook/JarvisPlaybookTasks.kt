package io.androllm.core.appatlas.playbook

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.remote.RemoteDataResult
import io.androllm.core.tools.remote.RemoteDataTaskHandler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber

/**
 * Branch W (Jarvis planner): typed Jarvis-link tasks that let Claude on the
 * Jarvis host list and run the phone's playbooks WITHOUT an agent turn on the
 * phone (no local model in the loop, no slot values through a prompt).
 *
 * - `playbooks.list`  body `{"package"?: str}` → `{"playbooks":[…],"more":bool}` (< 7500 chars):
 *   id, package, name, params (name/type/required/enum values), effective status, risky,
 *   verified (succeeded here with the postcondition seen), source, versions. Protected and
 *   not-installed apps are never listed.
 * - `playbook.run`    body `{"id": str, "args"?: {name: value}}` → result JSON
 *   `{"outcome":"ok|denied|timeout|failed|refused|invalid", …}` (ids, counts, machine reasons only).
 *
 * Safety (on top of everything [PlaybookRunner] already enforces live — protected apps,
 * installed version, stale → no replay, remote → no ui_run fallback):
 * - only when "Accept playbooks from Jarvis" and the App Atlas are on;
 * - only a playbook TRUSTED on this device ([PlaybookPolicy.fastPathTrusted]: imported from a
 *   file by the owner, or already verified by an owner run) and with effective status OK
 *   (never stale / app-updated / outside the learned version range);
 * - the run happens INSIDE [RemoteToolOrigin]: every risky step raises the remote-labelled
 *   TAP-ONLY card (mirrored to a heads-up notification by RemoteConfirmationRouter); no
 *   answer within [ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS] denies. Nothing is
 *   ever auto-approved here;
 * - the run never earns trust (remote runs never count as verified — gate Q N2).
 *
 * Logs: playbook id and outcome only — never slot values.
 */
@Singleton
class JarvisPlaybookTaskHandler(
    private val repository: PlaybookRepository,
    private val runPlaybook: suspend (String, Map<String, String?>) -> ToolResult,
    private val installedVersion: suspend (String) -> Long?,
    private val appLabel: suspend (String) -> String?,
    private val atlasSensitive: suspend (String) -> Boolean,
    private val enabled: () -> Boolean,
    private val ownPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS
) : RemoteDataTaskHandler {

    @Inject constructor(
        @ApplicationContext context: Context,
        repository: PlaybookRepository,
        runner: PlaybookRunner,
        atlas: AppAtlasRepository,
        settings: io.androllm.core.appatlas.settings.AppAtlasSettingsStore
    ) : this(
        repository = repository,
        runPlaybook = { id, args -> runner.run(id, args, allowFallback = false) },
        installedVersion = { pkg -> atlas.entry(pkg)?.versionCode },
        appLabel = { pkg -> atlas.cachedEntry(pkg)?.label },
        atlasSensitive = { pkg -> atlas.entry(pkg)?.sensitive == true },
        enabled = { atlas.isEnabled && settings.current().acceptRemotePlaybooks },
        ownPackage = context.packageName
    )

    override val types: Set<String> = setOf(TYPE_LIST, TYPE_RUN)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult {
        if (!enabled()) return RemoteDataResult(false, outcome("refused", "disabled"), status = STATUS_DENIED)
        return when (type) {
            TYPE_LIST -> list(body)
            TYPE_RUN -> run(body)
            else -> RemoteDataResult(false, "unknown task type")
        }
    }

    private suspend fun protected(pkg: String, label: String?): Boolean =
        pkg == ownPackage || atlasSensitive(pkg) ||
            SensitiveClassifier.reason(pkg, label.orEmpty(), repository.extras()) != null

    private suspend fun list(body: JsonObject?): RemoteDataResult {
        val onlyPkg = (body?.get("package") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val items = repository.all()
            .filter { onlyPkg == null || it.playbook.packageName == onlyPkg }
            .sortedWith(compareBy({ it.playbook.packageName }, { it.playbook.name }))
        val entries = ArrayList<String>()
        var more = false
        var used = 40
        for (sp in items) {
            val p = sp.playbook
            val label = appLabel(p.packageName)
            if (protected(p.packageName, listOfNotNull(p.appLabel, label).joinToString(" "))) continue
            val vc = installedVersion(p.packageName) ?: continue
            val e = buildJsonObject {
                put("id", p.id)
                put("package", p.packageName)
                put("name", p.name)
                put("description", io.androllm.core.appatlas.index.AtlasText.clean(p.description, 120) ?: "")
                putJsonArray("params") {
                    p.params.forEach { prm ->
                        addJsonObject {
                            put("name", prm.name); put("type", prm.type); put("required", prm.required)
                            if (prm.values.isNotEmpty()) putJsonArray("values") { prm.values.forEach { add(it) } }
                        }
                    }
                }
                put("status", PlaybookPolicy.effectiveStatus(sp, vc).name.lowercase())
                put("risky", p.isRisky)
                put("verified", sp.verifiedCount > 0)
                put("trusted", PlaybookPolicy.fastPathTrusted(sp))
                put("source", sp.source)
                put("installedVc", vc)
                put("versionMin", p.versionMin)
                put("versionMax", p.versionMax)
            }.toString()
            if (used + e.length + 1 > LIST_MAX_CHARS) { more = true; break }
            entries += e
            used += e.length + 1
        }
        return RemoteDataResult(true, "{\"playbooks\":[${entries.joinToString(",")}],\"more\":$more}")
    }

    private suspend fun run(body: JsonObject?): RemoteDataResult {
        val id = (body?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return RemoteDataResult(false, outcome("invalid", "missing id"), status = STATUS_INVALID)
        val argsEl = body["args"]
        if (argsEl != null && argsEl !is JsonObject) {
            return RemoteDataResult(false, outcome("invalid", "args must be an object"), status = STATUS_INVALID)
        }
        val args = (argsEl as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull } ?: emptyMap()
        val sp = repository.get(id)
            ?: return RemoteDataResult(false, outcome("refused", "no such playbook", id), status = STATUS_DENIED)
        val p = sp.playbook
        if (protected(p.packageName, listOfNotNull(p.appLabel, appLabel(p.packageName)).joinToString(" "))) {
            return RemoteDataResult(false, outcome("refused", "protected app", id), status = STATUS_DENIED)
        }
        val vc = installedVersion(p.packageName)
            ?: return RemoteDataResult(false, outcome("refused", "not installed", id), status = STATUS_DENIED)
        if (!PlaybookPolicy.fastPathTrusted(sp)) {
            return RemoteDataResult(false, outcome("refused", "not verified on this device", id), status = STATUS_DENIED)
        }
        val status = PlaybookPolicy.effectiveStatus(sp, vc)
        if (status != PlaybookStatus.OK) {
            return RemoteDataResult(false, outcome("refused", "status ${status.name.lowercase()}", id), status = STATUS_DENIED)
        }
        val started = clock()
        val r = withContext(RemoteToolOrigin(label = REMOTE_LABEL, ownPackages = setOf(ownPackage))) {
            runPlaybook(id, args)
        }
        val elapsed = clock() - started
        return when (r) {
            is ToolResult.Success -> {
                Timber.i("Jarvis playbook.run %s: ok", id)
                RemoteDataResult(true, buildJsonObject {
                    put("outcome", "ok"); put("playbook", id)
                    r.data.forEach { (k, v) -> if (k in OK_KEYS) put(k, v) }
                }.toString())
            }
            is ToolResult.Failure -> {
                val declined = r.summary.startsWith("The user declined")
                val kind = when {
                    declined && elapsed >= confirmTimeoutMs - TIMEOUT_SLACK_MS -> "timeout"
                    declined -> "denied"
                    r.summary.startsWith("Missing required") || r.summary.contains("Params:") -> "invalid"
                    else -> "failed"
                }
                Timber.i("Jarvis playbook.run %s: %s", id, kind)
                val text = buildJsonObject {
                    put("outcome", kind); put("playbook", id)
                    r.data.forEach { (k, v) -> if (k in FAIL_KEYS) put(k, v) }
                }.toString()
                RemoteDataResult(
                    false, text,
                    status = when (kind) { "denied", "timeout" -> STATUS_DENIED; "invalid" -> STATUS_INVALID; else -> STATUS_ERROR }
                )
            }
        }
    }

    private fun outcome(kind: String, reason: String, id: String? = null) = buildJsonObject {
        put("outcome", kind); put("reason", reason); if (id != null) put("playbook", id)
    }.toString()

    companion object {
        const val TYPE_LIST = "playbooks.list"
        const val TYPE_RUN = "playbook.run"
        const val REMOTE_LABEL = "Jarvis · плейбук"
        const val LIST_MAX_CHARS = 7_500
        const val STATUS_DENIED = "denied"
        const val STATUS_INVALID = "invalid"
        const val STATUS_ERROR = "error"
        /** A decline this close to the card timeout is reported as `timeout` (the manager denies on timeout). */
        const val TIMEOUT_SLACK_MS = 5_000L
        private val OK_KEYS = setOf("verified", "steps", "elapsed_ms", "mode")
        private val FAIL_KEYS = setOf("failed_step", "failed_op", "reason", "steps_done", "steps_total")
    }
}
