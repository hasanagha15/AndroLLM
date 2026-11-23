package io.androllm.core.appatlas.learn

import io.androllm.core.appatlas.AppAtlasRepository
import io.androllm.core.appatlas.model.LearnedPath
import io.androllm.core.appatlas.search.Concepts
import io.androllm.core.appatlas.settings.AppAtlasSettingsStore
import io.androllm.core.tools.uistep.UiStep
import io.androllm.core.tools.uistep.UiStepJournal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens to [UiStepJournal] and records replayable element paths after a
 * successful UI task (see [PathSessionTracker] / [LearnedPathPolicy] for the
 * rules). Off when Settings → App Atlas → "Record learned paths" is off.
 */
@Singleton
class LearnedPathRecorder @Inject constructor(
    private val repository: AppAtlasRepository,
    private val settingsStore: AppAtlasSettingsStore
) : UiStepJournal.Listener {

    private val tracker = PathSessionTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        UiStepJournal.register(this)
    }

    private val active: Boolean
        get() = settingsStore.current().let { it.enabled && it.recordPaths }

    /** app_atlas_search saw these concepts — a following model-driven sequence is keyed by them. */
    fun noteQuery(query: String) {
        tracker.pendingTaskKey = Concepts.taskKey(query)
    }

    /** app_open_action opened [pkg]: a new task session starts there. */
    fun beginSession(pkg: String, versionCode: Long, query: String?) {
        if (!active) return
        persist(tracker.begin(pkg, versionCode, Concepts.taskKey(query)))
    }

    override fun onStep(step: UiStep) {
        if (!active) return
        persist(tracker.onStep(step, repository.cachedEntry(step.packageName)))
    }

    override fun onTaskFinished(goal: String, success: Boolean) {
        if (!active) return
        persist(tracker.onTaskFinished(goal, success) { repository.cachedEntry(it) })
    }

    private fun persist(paths: List<LearnedPath>) {
        if (paths.isEmpty()) return
        scope.launch { repository.recordPaths(paths) }
    }
}
