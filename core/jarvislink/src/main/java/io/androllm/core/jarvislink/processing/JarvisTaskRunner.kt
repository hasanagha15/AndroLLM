package io.androllm.core.jarvislink.processing

import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.settings.AutomationSettings
import io.androllm.core.tools.settings.ConfirmationMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Final outcome of one remote task. */
data class TaskOutcome(val status: ResultStatus, val text: String)

/** Runs one remote task and returns what goes back to Jarvis. */
fun interface JarvisTaskRunner {
    suspend fun run(task: JarvisTask): TaskOutcome
}

/**
 * One agent turn in the app's chat pipeline (adapter over ChatManager, bound in
 * the app module). Implementations MUST use a dedicated, non-active
 * conversation without memory and without the conversation history.
 */
fun interface RemoteTurnExecutor {
    fun run(prompt: String): Flow<String>
}

/** The remote task currently executing (null when idle). */
@Singleton
class RemoteTaskTracker @Inject constructor() {
    private val _active = MutableStateFlow<JarvisTask?>(null)
    val active: StateFlow<JarvisTask?> = _active.asStateFlow()

    fun begin(task: JarvisTask) { _active.value = task }
    fun end(task: JarvisTask) { if (_active.value?.id == task.id) _active.value = null }
}

/**
 * The real runner: one remote task = one agent turn, executed INSIDE
 * [RemoteToolOrigin]. That context element makes ToolExecutor apply the remote
 * policy on every single tool call (read-only tools only without a tap; NEVER
 * mode refused; no calls aimed at AndroLLM itself) and makes the confirmation
 * manager skip the voice responder. The runner itself never approves anything.
 *
 * Before starting it waits (bounded) until no owner turn is running, so remote
 * work does not interleave with the owner's own requests.
 */
class RemoteAgentRunner(
    private val turns: RemoteTurnExecutor,
    private val automationSettings: suspend () -> AutomationSettings,
    private val ownerBusy: StateFlow<Boolean>,
    /** A FRESH origin (with its own variable map) is created for every task. */
    private val originFactory: () -> RemoteToolOrigin = { RemoteToolOrigin() },
    private val idleWaitMs: Long = IDLE_WAIT_MS,
    private val taskTimeoutMs: Long = TASK_TIMEOUT_MS
) : JarvisTaskRunner {

    override suspend fun run(task: JarvisTask): TaskOutcome {
        if (automationSettings().confirmationMode == ConfirmationMode.NEVER) {
            return TaskOutcome(ResultStatus.DENIED, NEVER_REFUSAL)
        }
        if (withTimeoutOrNull(idleWaitMs) { ownerBusy.first { !it } } == null) {
            Timber.w("JarvisLink: owner still busy after %d ms — running %s anyway (cards are queued)", idleWaitMs, task.id)
        }
        val answer = StringBuilder()
        val finished = try {
            withContext(originFactory()) {
                withTimeoutOrNull(taskTimeoutMs) {
                    turns.run("$PROMPT_PREFIX${task.text}").collect { answer.append(it) }
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "JarvisLink: turn failed for %s", task.id)
            return TaskOutcome(ResultStatus.ERROR, "Task failed on the phone.")
        }
        return classify(answer.toString().trim(), finished != null)
    }

    companion object {
        const val IDLE_WAIT_MS = 180_000L
        /** Covers several confirmation waits (each up to 5 min) plus generation. */
        const val TASK_TIMEOUT_MS = 15 * 60_000L
        const val PROMPT_PREFIX = "[Удалённая задача от Jarvis] "
        const val NEVER_REFUSAL =
            "Refused: on the phone Settings → Automation → Confirmations is 'Never'. Remote tasks need confirmations enabled."

        /** Only the final answer text goes back; pipeline error strings become `error` without details. */
        fun classify(text: String, finished: Boolean): TaskOutcome = when {
            !finished -> TaskOutcome(ResultStatus.ERROR, "Timed out on the phone.")
            text.isEmpty() -> TaskOutcome(ResultStatus.ERROR, "The assistant produced no answer.")
            text.startsWith("Error:") -> TaskOutcome(ResultStatus.ERROR, "Task failed on the phone.")
            text.startsWith("I don't have a model loaded") -> TaskOutcome(ResultStatus.ERROR, "No model is loaded on the phone.")
            else -> TaskOutcome(ResultStatus.OK, text)
        }
    }
}
