package io.androllm.core.appatlas.playbook

import io.androllm.core.accessibility.executor.AutomationExecutor
import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.index.SensitiveClassifier
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.origin.currentRemoteOrigin
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import timber.log.Timber

/** Result of the model-driven fallback (ui_run). */
data class FallbackOutcome(val success: Boolean, val summary: String, val retryable: Boolean = true)

/**
 * Runs one playbook end to end: live protection check → installed version →
 * effective status (OK / UNVERIFIED = strict / STALE = no replay) → slot
 * filling → deterministic replay → outcome bookkeeping (stale + relearn on
 * mismatch) → optional model-driven ui_run fallback. Shared by playbook_run
 * and the zero-LLM fast path. One run at a time.
 *
 * Logging: playbook id, step index and failure kind only — never slot values.
 */
@Singleton
class PlaybookRunner(
    private val repository: PlaybookRepository,
    private val driver: PlaybookDriver,
    private val installedVersion: suspend (String) -> Long?,
    private val appLabel: suspend (String) -> String?,
    private val fallback: (suspend (String) -> FallbackOutcome)?,
    private val enabled: () -> Boolean = { true },
    /** Gate N-B3: the atlas flag (guard list, name heuristic, payment-scheme handlers). */
    private val atlasSensitive: suspend (String) -> Boolean = { false }
) {
    @Inject constructor(
        repository: PlaybookRepository,
        driver: AccessibilityPlaybookDriver,
        atlas: AppAtlasRepository,
        executor: AutomationExecutor
    ) : this(
        repository = repository,
        driver = driver,
        installedVersion = { pkg -> atlas.entry(pkg)?.versionCode },
        appLabel = { pkg -> atlas.cachedEntry(pkg)?.label },
        fallback = { goal ->
            val r = executor.run(goal)
            io.androllm.core.tools.uistep.UiStepJournal.taskFinished(goal, r.success)
            FallbackOutcome(r.success, r.summary, r.retryable)
        },
        enabled = { atlas.isEnabled },
        atlasSensitive = { pkg -> atlas.entry(pkg)?.sensitive == true }
    )

    private val engine = PlaybookEngine(driver)
    private val mutex = Mutex()

    suspend fun run(id: String, args: Map<String, String?>, allowFallback: Boolean): ToolResult {
        if (!enabled()) return ToolResult.Failure("The App Atlas is turned off in Settings.", retryable = false)
        val sp = repository.get(id)
            ?: return ToolResult.Failure("No playbook '$id'. Call playbook_list first.", retryable = false)
        val slots = when (val f = PlaybookSlots.fill(sp.playbook, args)) {
            is SlotFill.Ok -> f.slots
            is SlotFill.Error -> return ToolResult.Failure("${f.reason}. Params: ${PlaybookFormat.signature(sp.playbook)}", retryable = true)
        }
        return runResolved(sp, slots, allowFallback)
    }

    suspend fun runResolved(sp: StoredPlaybook, slots: SlotValues, allowFallback: Boolean): ToolResult = mutex.withLock {
        val p = sp.playbook
        // Live protection: the owner's list / guard / heuristic may have changed since import.
        val label = appLabel(p.packageName)
        if (atlasSensitive(p.packageName)) {
            return ToolResult.Failure("${p.packageName} is a protected app (banking / payment): no playbooks, no automation.", retryable = false)
        }
        SensitiveClassifier.reason(p.packageName, listOfNotNull(p.appLabel, label).joinToString(" "), repository.extras())?.let {
            return ToolResult.Failure("${p.packageName} is a protected app ($it): no playbooks, no automation.", retryable = false)
        }
        val version = installedVersion(p.packageName)
            ?: return ToolResult.Failure("${p.packageName} is not installed (or excluded from the atlas).", retryable = false)
        val status = PlaybookPolicy.effectiveStatus(sp, version)
        if (status == PlaybookStatus.STALE) {
            Timber.i("Playbooks: %s stale on v%d — not replayed", p.id, version)
            return fallbackOr(p, slots, allowFallback, "Playbook ${p.id} is stale on this app version (relearn requested).")
        }
        // Update seen before the atlas sync did: flag + queue the relearn request once.
        if (status == PlaybookStatus.UNVERIFIED && (sp.status != PlaybookStatus.UNVERIFIED || sp.flaggedVersion != version)) {
            repository.onAppVersion(p.packageName, version)
        }

        val r = engine.run(p, slots, strict = status == PlaybookStatus.UNVERIFIED)
        if (r.success) {
            repository.recordSuccess(p.id)
            if (r.hasPostcondition && !r.verified) repository.recordUnverified(p.id, version)
            Timber.i("Playbooks: %s ok in %d ms (verified=%s)", p.id, r.elapsedMs, r.verified)
            return ToolResult.Success(
                summary = buildString {
                    append("Done: ").append(p.id).append(" (").append(r.stepsDone).append(" steps, ").append(r.elapsedMs).append(" ms")
                    append(if (r.hasPostcondition) ", verified=${r.verified}" else ", no postcondition").append(").")
                    if (status == PlaybookStatus.UNVERIFIED) append(" App version is outside the learned range; ran in strict mode.")
                    if (r.hasPostcondition && !r.verified) append(" The final screen was not confirmed - check with ui_read_screen.")
                },
                data = buildJsonObject {
                    put("status", "ok")
                    put("playbook", p.id)
                    put("verified", r.verified)
                    put("steps", r.stepsDone)
                    put("elapsed_ms", r.elapsedMs)
                    put("mode", if (status == PlaybookStatus.UNVERIFIED) "strict" else "normal")
                    putJsonArray("locators") { r.locatorStats.forEach { add(it) } }
                }
            )
        }
        val f = r.failure ?: StepFailure(r.stepsDone, "?", FailureKind.ACTION_FAILED)
        Timber.i("Playbooks: %s stopped at step %d (%s)", p.id, f.stepIndex, f.kind.wire)
        val base = "Playbook ${p.id} stopped at ${f.describe()}."
        return when {
            f.kind == FailureKind.DECLINED -> {
                repository.recordFailure(p.id)
                ToolResult.Failure("The user declined the action.", retryable = false)
            }
            f.kind == FailureKind.BLOCKED -> {
                repository.recordFailure(p.id)
                ToolResult.Failure("$base A protected app is on screen; automation is blocked there.", retryable = false)
            }
            f.kind == FailureKind.SERVICE_OFF -> ToolResult.Failure(
                "The accessibility service is not enabled. Settings → Accessibility → AndroLLM UI Automation.", retryable = false
            )
            f.kind.mismatch -> {
                repository.recordMismatch(p.id, version, f)
                fallbackOr(p, slots, allowFallback, "$base Marked stale for v$version; relearn requested.", f)
            }
            else -> {
                repository.recordFailure(p.id)
                fallbackOr(p, slots, allowFallback, base, f)
            }
        }
    }

    private suspend fun fallbackOr(p: Playbook, slots: SlotValues, allow: Boolean, why: String, f: StepFailure? = null): ToolResult {
        val data = buildJsonObject {
            put("status", "failed")
            put("playbook", p.id)
            put("verified", false)
            f?.let { put("failed_step", it.stepIndex); put("reason", it.kind.wire) }
        }
        // Gate N-B1: RemoteToolPolicy forbids ui_run for remote (Jarvis) turns — so no fallback either.
        if (currentRemoteOrigin() != null) {
            repository.requestRelearn(p, installedVersion(p.packageName) ?: 0L, f?.stepIndex ?: -1, f?.kind?.wire ?: "stale")
            return ToolResult.Failure(
                "$why Remote task: no ui_run fallback (not allowed for remote tasks); relearn requested.",
                data = data, retryable = false
            )
        }
        val fb = fallback
        if (!allow || fb == null) {
            return ToolResult.Failure("$why Use app_atlas_search + ui_run instead.", data = data, retryable = false)
        }
        // The fallback goal carries the slot values to the ui_run planner (same as a direct ui_run call); not stored.
        val out = fb(fallbackGoal(p, slots))
        return if (out.success) {
            ToolResult.Success("$why Fell back to ui_run: ${out.summary}", data)
        } else {
            ToolResult.Failure("$why Fallback ui_run failed: ${out.summary}", data = data, retryable = false)
        }
    }

    private suspend fun fallbackGoal(p: Playbook, slots: SlotValues): String {
        val app = p.appLabel ?: appLabel(p.packageName) ?: p.packageName
        val params = p.params.mapNotNull { prm -> slots[prm.name]?.let { "${prm.name}: $it" } }
        return "In $app: ${p.description}" + if (params.isEmpty()) "" else " (${params.joinToString("; ")})"
    }
}

/** Compact renderings for the model (small context). */
object PlaybookFormat {
    fun signature(p: Playbook): String =
        p.params.joinToString(", ", "(", ")") { if (it.required) it.name else "${it.name}?" }

    /** "com.app/search(query) — Search videos [unverified] (asks to confirm)" */
    fun line(sp: StoredPlaybook, installedVersion: Long?): String {
        val p = sp.playbook
        val status = PlaybookPolicy.effectiveStatus(sp, installedVersion)
        return buildString {
            append(p.id).append(signature(p)).append(" — ").append(io.androllm.core.appatlas.index.AtlasText.clean(p.description, 80) ?: "")
            if (status != PlaybookStatus.OK) append(" [").append(status.name.lowercase()).append(']')
            if (p.isRisky) append(" (asks to confirm)")
        }
    }
}
