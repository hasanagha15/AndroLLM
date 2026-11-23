package io.androllm.core.appatlas.learn

import io.androllm.core.accessibility.controller.SensitiveAppGuard
import io.androllm.core.appatlas.model.AppAtlasEntry
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.model.PathStep
import io.androllm.core.appatlas.search.Concepts
import io.androllm.core.tools.uistep.UiStep

/** Pure rules for learned paths: privacy filtering, merging, version invalidation. */
object LearnedPathPolicy {

    const val MAX_STEPS = 15
    private val DIRECTIONS = setOf("up", "down", "left", "right")

    /**
     * Converts a raw [UiStep] into a storable [PathStep], or null when the step
     * cannot be replayed WITHOUT storing user data:
     * - the view id is kept only as its entry name and only when it belongs to
     *   the app (or the framework);
     * - the label is kept only when it is one of the app's own UI strings /
     *   view ids from the atlas — so a contact name, a message or any screen
     *   text never reaches storage;
     * - typed text is never part of a [UiStep] in the first place.
     */
    fun sanitize(step: UiStep, entry: AppAtlasEntry?): PathStep? {
        return when (step.op) {
            UiStep.Op.SCROLL -> step.label?.lowercase()?.takeIf { it in DIRECTIONS }
                ?.let { PathStep(op = "scroll", arg = it) }
            UiStep.Op.TAP, UiStep.Op.TYPE -> {
                val id = idEntry(step.resourceId, step.packageName)
                val label = step.label?.trim()?.takeIf { l ->
                    entry != null && l.isNotEmpty() &&
                        (entry.uiStrings.any { it.equals(l, ignoreCase = true) } ||
                            entry.uiIds.any { it.equals(l, ignoreCase = true) })
                }
                if (id == null && label == null) null
                else PathStep(op = if (step.op == UiStep.Op.TAP) "tap" else "type", id = id, label = label)
            }
        }
    }

    internal fun idEntry(resourceId: String?, pkg: String): String? {
        val rid = resourceId?.trim().orEmpty()
        if (rid.isEmpty()) return null
        val owner = rid.substringBefore(":id/", missingDelimiterValue = "")
        if (owner != pkg && owner != "android") return null
        val name = rid.substringAfter(":id/")
        return name.takeIf { it.isNotEmpty() && it.length <= 60 && it.all { c -> c.isLetterOrDigit() || c == '_' } }
    }

    /** Merges a newly recorded path into the stored one for the same (package, task). */
    fun merge(existing: LearnedPath?, recorded: LearnedPath): LearnedPath {
        if (existing == null) return recorded
        if (existing.steps == recorded.steps) {
            return existing.copy(
                versionCode = recorded.versionCode,
                successCount = existing.successCount + 1,
                verified = existing.verified || recorded.verified,
                stale = false,
                updatedAt = recorded.updatedAt
            )
        }
        // A fresh verified path is not replaced by an unverified model-driven one.
        if (existing.verified && !existing.stale && !recorded.verified) return existing
        return recorded
    }

    /**
     * App updated to [newVersionCode]: paths recorded on another version are
     * kept but flagged stale (hint only, the screens may have moved).
     */
    fun onVersionChange(paths: List<LearnedPath>, newVersionCode: Long): List<LearnedPath> =
        paths.map { if (it.versionCode != newVersionCode && !it.stale) it.copy(stale = true) else it }

    /** Compact one-line rendering for tool output: tap #search_button → type #query {text} → scroll down. */
    fun render(path: LearnedPath, currentVersion: Long?): String {
        val steps = path.steps.joinToString(" → ") { s ->
            val target = s.id?.let { "#$it" } ?: s.label?.let { "\"$it\"" } ?: ""
            when (s.op) {
                "type" -> "type $target {text}".replace("  ", " ")
                "scroll" -> "scroll ${s.arg}"
                else -> "tap $target"
            }
        }
        val state = when {
            path.stale || (currentVersion != null && currentVersion != path.versionCode) -> "stale, recorded on v${path.versionCode} — verify on screen"
            path.verified -> "verified x${path.successCount}"
            else -> "x${path.successCount}"
        }
        return "${path.taskKey}: $steps ($state)"
    }
}

/**
 * Tracks the UI steps of the current task and decides when a sequence is a
 * successful, replayable path. Pure (clock injected); the recorder persists
 * what [onStep] / [onTaskFinished] / [begin] return.
 */
class PathSessionTracker(private val clock: () -> Long = System::currentTimeMillis) {

    private data class Session(
        val pkg: String,
        val taskKey: String?,
        val versionCode: Long,
        val steps: MutableList<PathStep> = mutableListOf(),
        var failed: Boolean = false,
        var unreplayable: Boolean = false,
        var lastAt: Long
    )

    private var session: Session? = null

    /** Concepts of the last atlas search (used when a model-driven sequence has no ui_run goal). */
    @Volatile var pendingTaskKey: String? = null

    /** An atlas action opened [pkg]; a new task starts there. */
    @Synchronized
    fun begin(pkg: String, versionCode: Long, taskKey: String?): List<LearnedPath> {
        val done = finalizeManual()
        session = Session(pkg, taskKey ?: pendingTaskKey, versionCode, lastAt = clock())
        return done
    }

    @Synchronized
    fun onStep(step: UiStep, entry: AppAtlasEntry?): List<LearnedPath> {
        val now = clock()
        val out = mutableListOf<LearnedPath>()
        var s = session
        if (s != null && (s.pkg != step.packageName || now - s.lastAt > IDLE_MS)) {
            out += finalizeManual()
            s = null
        }
        if (s == null) {
            s = Session(step.packageName, pendingTaskKey, entry?.versionCode ?: 0L, lastAt = now)
            session = s
        }
        s.lastAt = now
        if (isSensitive(step.packageName, entry)) {
            s.unreplayable = true
            return out
        }
        if (!step.success) {
            s.failed = true
            return out
        }
        val ps = LearnedPathPolicy.sanitize(step, entry)
        if (ps == null || s.steps.size >= LearnedPathPolicy.MAX_STEPS) s.unreplayable = true
        else s.steps += ps
        return out
    }

    /** A ui_run finished: commit a verified path when everything succeeded. */
    @Synchronized
    fun onTaskFinished(goal: String, success: Boolean, entryFor: (String) -> AppAtlasEntry?): List<LearnedPath> {
        val s = session ?: return emptyList()
        session = null
        val key = Concepts.taskKey(goal) ?: s.taskKey ?: return emptyList()
        if (!success || !valid(s, entryFor(s.pkg))) return emptyList()
        return listOf(toPath(s, key, verified = true))
    }

    /** Closes the current session; a clean multi-step model-driven sequence becomes an unverified path. */
    @Synchronized
    fun finalizeManual(): List<LearnedPath> {
        val s = session ?: return emptyList()
        session = null
        val key = s.taskKey ?: return emptyList()
        if (s.steps.size < 2 || !valid(s, null)) return emptyList()
        return listOf(toPath(s, key, verified = false))
    }

    private fun valid(s: Session, entry: AppAtlasEntry?): Boolean =
        !s.failed && !s.unreplayable && s.steps.isNotEmpty() && !isSensitive(s.pkg, entry)

    private fun isSensitive(pkg: String, entry: AppAtlasEntry?): Boolean =
        entry?.sensitive == true || SensitiveAppGuard.blockedReason(pkg) != null

    private fun toPath(s: Session, key: String, verified: Boolean) = LearnedPath(
        packageName = s.pkg,
        taskKey = key,
        versionCode = s.versionCode,
        steps = s.steps.toList(),
        verified = verified,
        updatedAt = clock()
    )

    companion object {
        const val IDLE_MS = 120_000L
    }
}
