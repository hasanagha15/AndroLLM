package io.androllm.app.jarvislink

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.models.MessageOrigin
import io.androllm.core.tools.settings.AutomationSettingsStore
import io.androllm.core.tools.settings.ConfirmationMode
import io.androllm.feature.voice.chat.ChatManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Branch b-mqtt hook: a task received from Jarvis runs through the SAME chat
 * pipeline as a typed or spoken request ([ChatManager] → ToolRunCoordinator →
 * ToolExecutor). Dangerous tools therefore hit the unchanged confirmation gate
 * (ToolConfirmationManager): the chat card, the voice responder and the Jarvis
 * notification are all just surfaces of that one decision. Nothing here can
 * approve anything.
 *
 * Extra rule for the remote channel: when the user has set confirmations to
 * "Never", remote tasks are refused outright (a typed request is the user's
 * own action; a remote one must never run side effects unattended).
 */
@Singleton
class ChatPipelineJarvisTaskRunner @Inject constructor(
    private val chatManager: ChatManager,
    private val automationSettings: AutomationSettingsStore
) : JarvisTaskRunner {

    override suspend fun run(task: JarvisTask): TaskOutcome {
        if (automationSettings.current().confirmationMode == ConfirmationMode.NEVER) {
            Timber.w("JarvisLink: task %s refused — confirmation mode is NEVER", task.id)
            return TaskOutcome(
                ResultStatus.DENIED,
                "Refused: on the phone Settings → Automation → Confirmations is set to 'Never'. " +
                    "Remote tasks only run with confirmations enabled."
            )
        }
        val answer = StringBuilder()
        val finished = withTimeoutOrNull(TASK_TIMEOUT_MS) {
            chatManager.sendMessageStream(task.text, origin = MessageOrigin.AUTOMATION)
                .collect { delta -> answer.append(delta) }
            true
        }
        val text = answer.toString().trim()
        return when {
            finished == null -> TaskOutcome(ResultStatus.ERROR, "Timed out after ${TASK_TIMEOUT_MS / 60_000} min. Partial answer: $text")
            text.isEmpty() -> TaskOutcome(ResultStatus.ERROR, "The assistant produced no answer.")
            else -> TaskOutcome(ResultStatus.OK, text)
        }
    }

    companion object {
        /** Covers several confirmation waits (each up to 5 min) plus generation. */
        const val TASK_TIMEOUT_MS = 15 * 60_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class JarvisLinkHookModule {
    @Binds
    @Singleton
    abstract fun bindJarvisTaskRunner(impl: ChatPipelineJarvisTaskRunner): JarvisTaskRunner
}
