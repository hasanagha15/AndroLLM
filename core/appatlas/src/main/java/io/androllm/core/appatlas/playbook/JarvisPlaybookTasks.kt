package io.androllm.core.appatlas.playbook

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.accessibility.controller.AccessibilityController
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber

/** Branch W (gate N3): why the phone should not be driven right now; null = free. */
fun interface OwnerActivity {
    /** "owner_active" | "screen_locked" | "screen_off" | null. */
    fun busyReason(): String?
}

/**
 * Branch W (Jarvis planner): typed Jarvis-link tasks that let Claude on the
 * Jarvis host list and run the phone's playbooks WITHOUT an agent turn on the
 * phone (no local model in the loop, no slot values through a prompt).
 *
 * - `playbooks.list`  body `{"package"?: str}` → `{"playbooks":[…],"more":bool}` (< 7500 chars).
 *   Needs "Accept playbooks from Jarvis". Protected and not-installed apps are never listed.
 * - `playbook.run`    body `{"id": str, "args"?: {name: value}, "deadline_ms": epoch ms}` → result JSON
 *   `{"outcome":"ok|denied|timeout|failed|refused|busy|invalid","reason"?: code, …}` (ids, counts, machine codes only).
 *
 * Safety of `playbook.run` (on top of everything [PlaybookRunner] enforces live — protected apps,
 * installed version, stale → no replay, remote → no ui_run fallback):
 * - its OWN owner setting "Jarvis may run verified playbooks" (`allowJarvisPlaybookRun`,
 *   OFF by default, gate B1) plus the App Atlas; the import flag never enables runs;
 * - only a playbook TRUSTED on this device ([PlaybookPolicy.fastPathTrusted]: imported from a file by the
 *   owner, or verified once by an owner run — with ANY slot values afterwards, gate N9) with effective status
 *   OK, re-checked under the runner mutex (gate N2);
 * - `busy` while someone is using the phone or it is locked / off (gate N3);
 * - a hard deadline (gate B4): `deadline_ms` from Jarvis, capped at now + [MAX_RUN_WINDOW_MS]. Nothing starts
 *   after it; the whole replay — every step and every card wait — is cancelled at the deadline
 *   (`outcome:"timeout","reason":"deadline"`), so Jarvis, which waits longer than the deadline, always sees
 *   the final result and nothing runs after it gave up;
 * - the run happens INSIDE [RemoteToolOrigin]: every risky step raises the remote-labelled TAP-ONLY card
 *   (heads-up notification). Nothing is ever auto-approved here;
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
    private val listEnabled: () -> Boolean,
    private val runEnabled: () -> Boolean,
    private val ownPackage: String,
    private val owner: OwnerActivity = OwnerActivity { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS
) : RemoteDataTaskHandler {

    @Inject constructor(
        @ApplicationContext context: Context,
        repository: PlaybookRepository,
        runner: PlaybookRunner,
        atlas: AppAtlasRepository,
        settings: io.androllm.core.appatlas.settings.AppAtlasSettingsStore,
        controller: AccessibilityController
    ) : this(
        repository = repository,
        runPlaybook = { id, args -> runner.run(id, args, allowFallback = false, requireTrustedOk = true) },
        installedVersion = { pkg -> atlas.entry(pkg)?.versionCode },
        appLabel = { pkg -> atlas.cachedEntry(pkg)?.label },
        atlasSensitive = { pkg -> atlas.entry(pkg)?.sensitive == true },
        listEnabled = { atlas.isEnabled && settings.current().acceptRemotePlaybooks },
        runEnabled = { atlas.isEnabled && settings.current().allowJarvisPlaybookRun },
        ownPackage = context.packageName,
        owner = deviceOwnerActivity(context) { controller.lastEventAtElapsedMs }
    )

    override val types: Set<String> = setOf(TYPE_LIST, TYPE_RUN)

    override suspend fun handle(type: String, body: JsonObject?): RemoteDataResult = when (type) {
        TYPE_LIST -> if (listEnabled()) list(body) else refused("disabled")
        TYPE_RUN -> if (runEnabled()) run(body) else refused("disabled")
        else -> RemoteDataResult(false, "unknown task type")
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
            ?: return invalid("missing id")
        val argsEl = body["args"]
        if (argsEl != null && argsEl !is JsonObject) return invalid("args must be an object")
        val deadlineIn = (body["deadline_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: return invalid("missing deadline_ms")
        val args = (argsEl as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull } ?: emptyMap()
        val start = clock()
        val deadline = minOf(deadlineIn, start + MAX_RUN_WINDOW_MS)
        if (deadline <= start) return finish(id, "timeout", "deadline", STATUS_DENIED)

        val sp = repository.get(id) ?: return refused("playbook_not_found", id)
        val p = sp.playbook
        if (protected(p.packageName, listOfNotNull(p.appLabel, appLabel(p.packageName)).joinToString(" "))) {
            return refused("protected", id)
        }
        val vc = installedVersion(p.packageName) ?: return refused("not_installed", id)
        if (!PlaybookPolicy.fastPathTrusted(sp)) return refused("not_trusted", id)
        val status = PlaybookPolicy.effectiveStatus(sp, vc)
        if (status != PlaybookStatus.OK) return refused("status_${status.name.lowercase()}", id)
        owner.busyReason()?.let { return finish(id, "busy", it, STATUS_BUSY) }

        val r = withTimeoutOrNull(deadline - clock()) {
            withContext(RemoteToolOrigin(label = REMOTE_LABEL, ownPackages = setOf(ownPackage))) {
                runPlaybook(id, args)
            }
        } ?: return finish(id, "timeout", "deadline", STATUS_DENIED)
        val elapsed = clock() - start
        return when (r) {
            is ToolResult.Success -> {
                Timber.i("Jarvis playbook.run %s: ok", id)
                RemoteDataResult(true, buildJsonObject {
                    put("outcome", "ok"); put("playbook", id)
                    r.data.forEach { (k, v) -> if (k in OK_KEYS) put(k, v) }
                }.toString())
            }
            is ToolResult.Failure -> failure(id, r, elapsed)
        }
    }

    private fun failure(id: String, r: ToolResult.Failure, elapsed: Long): RemoteDataResult {
        val dataReason = (r.data["reason"] as? JsonPrimitive)?.contentOrNull
        val refusedByRunner = (r.data["status"] as? JsonPrimitive)?.contentOrNull == "refused"
        return when {
            r.summary.startsWith("The user declined") ->
                // The manager denies on timeout; a decline this close to the card timeout is reported as such.
                if (elapsed >= confirmTimeoutMs - TIMEOUT_SLACK_MS) finish(id, "timeout", "card_timeout", STATUS_DENIED)
                else finish(id, "denied", "declined", STATUS_DENIED)
            refusedByRunner && dataReason != null -> refused(dataReason, id)
            r.summary.contains("protected app") -> refused("protected", id)
            r.summary.contains("not installed") -> refused("not_installed", id)
            r.summary.startsWith("Missing required") || r.summary.contains("Params:") -> invalid("bad_args", id)
            else -> {
                Timber.i("Jarvis playbook.run %s: failed", id)
                RemoteDataResult(false, buildJsonObject {
                    put("outcome", "failed"); put("playbook", id)
                    r.data.forEach { (k, v) -> if (k in FAIL_KEYS) put(k, v) }
                    if (dataReason == null) put("reason", "action_failed")
                }.toString(), status = STATUS_ERROR)
            }
        }
    }

    private fun finish(id: String, outcome: String, reason: String, status: String): RemoteDataResult {
        Timber.i("Jarvis playbook.run %s: %s/%s", id, outcome, reason)
        return RemoteDataResult(false, buildJsonObject {
            put("outcome", outcome); put("reason", reason); put("playbook", id)
        }.toString(), status = status)
    }

    private fun refused(reason: String, id: String? = null) = RemoteDataResult(false, buildJsonObject {
        put("outcome", "refused"); put("reason", reason); if (id != null) put("playbook", id)
    }.toString(), status = STATUS_DENIED)

    private fun invalid(reason: String, id: String? = null) = RemoteDataResult(false, buildJsonObject {
        put("outcome", "invalid"); put("reason", reason); if (id != null) put("playbook", id)
    }.toString(), status = STATUS_INVALID)

    companion object {
        const val TYPE_LIST = "playbooks.list"
        const val TYPE_RUN = "playbook.run"
        const val REMOTE_LABEL = "Jarvis · плейбук"
        const val LIST_MAX_CHARS = 7_500
        const val STATUS_DENIED = "denied"
        const val STATUS_INVALID = "invalid"
        const val STATUS_ERROR = "error"
        const val STATUS_BUSY = "busy"
        /** Longest run the phone accepts, whatever deadline Jarvis sends (one card wait + replay). */
        const val MAX_RUN_WINDOW_MS = 330_000L
        /** No UI event for this long on an unlocked, interactive screen = nobody is using the phone. */
        const val OWNER_IDLE_MS = 20_000L
        /** A decline this close to the card timeout is reported as `timeout` (the manager denies on timeout). */
        const val TIMEOUT_SLACK_MS = 5_000L
        private val OK_KEYS = setOf("verified", "steps", "elapsed_ms", "mode")
        private val FAIL_KEYS = setOf("failed_step", "failed_op", "reason", "steps_done", "steps_total")

        /** Production [OwnerActivity]: screen off / locked / recent UI events → busy. */
        fun deviceOwnerActivity(context: Context, lastEventAt: () -> Long): OwnerActivity = OwnerActivity {
            val pm = context.getSystemService(PowerManager::class.java)
            val km = context.getSystemService(KeyguardManager::class.java)
            when {
                pm == null || !pm.isInteractive -> "screen_off"
                km == null || km.isKeyguardLocked -> "screen_locked"
                SystemClock.elapsedRealtime() - lastEventAt() < OWNER_IDLE_MS -> "owner_active"
                else -> null
            }
        }
    }
}
