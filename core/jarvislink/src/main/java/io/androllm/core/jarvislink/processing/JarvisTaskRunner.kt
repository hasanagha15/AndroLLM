package io.androllm.core.jarvislink.processing

import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Final outcome of one remote task. */
data class TaskOutcome(val status: ResultStatus, val text: String)

/**
 * Runs a remote task through the app's agent pipeline.
 *
 * The implementation lives in the app module (JarvisLinkHook) and calls the
 * SAME chat pipeline a typed/spoken request uses — tool calls go through
 * `ToolExecutor`, whose confirmation gate (`ToolConfirmationManager`) asks the
 * user before any dangerous action. A runner must never execute tools itself
 * or approve confirmations on the user's behalf.
 */
fun interface JarvisTaskRunner {
    suspend fun run(task: JarvisTask): TaskOutcome
}

/** The remote task currently executing (null when idle). Drives the confirmation notification. */
@Singleton
class RemoteTaskTracker @Inject constructor() {
    private val _active = MutableStateFlow<JarvisTask?>(null)
    val active: StateFlow<JarvisTask?> = _active.asStateFlow()

    fun begin(task: JarvisTask) { _active.value = task }
    fun end(task: JarvisTask) { if (_active.value?.id == task.id) _active.value = null }
}
