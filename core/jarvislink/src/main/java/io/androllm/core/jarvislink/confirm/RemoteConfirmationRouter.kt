package io.androllm.core.jarvislink.confirm

import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Surface that shows a pending confirmation to the user (notification on device). */
interface ConfirmationNotifier {
    fun show(confirmation: PendingToolConfirmation, task: JarvisTask?)
    fun cancel()
}

/**
 * Mirrors REMOTE-origin confirmations (those carrying
 * [PendingToolConfirmation.remoteOrigin], set by ToolExecutor /
 * ToolConfirmationManager inside RemoteToolOrigin) to a heads-up notification.
 *
 * It adds a surface, never a decision path of its own:
 * - the decision is still `ToolConfirmationManager.awaitDecision`; the chat card
 *   stays live in parallel, first answer wins;
 * - confirmations of the owner's own typed/voice requests are NOT mirrored;
 * - [approve]/[deny] only act on the id pending right now and only for a
 *   remote-origin card (tap on our own notification; receiver not exported);
 * - no answer → the manager's timeout denies. Nothing approves automatically.
 */
@Singleton
class RemoteConfirmationRouter @Inject constructor(
    private val confirmationManager: ToolConfirmationManager,
    private val tracker: RemoteTaskTracker,
    private val notifier: ConfirmationNotifier
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(confirmationManager.pending, tracker.active) { pending, task -> pending to task }
                .distinctUntilChanged()
                .collect { (pending, task) ->
                    if (pending?.remoteOrigin != null) notifier.show(pending, task) else notifier.cancel()
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        notifier.cancel()
    }

    /** Gate V rev2 N1: AppFunction cards are approved only on the in-app card, never via the notification. */
    fun approve(id: String): Boolean {
        val pending = confirmationManager.pending.value
        if (pending != null && pending.id == id && AppFunctionConfirmHandler.isAppFunctionCard(pending)) return false
        return decide(id) { confirmationManager.confirm(it) }
    }

    fun deny(id: String): Boolean = decide(id) { confirmationManager.deny(it) }

    private inline fun decide(id: String, action: (String) -> Unit): Boolean {
        val pending = confirmationManager.pending.value ?: return false
        if (pending.id != id || pending.remoteOrigin == null) return false
        action(id)
        return true
    }
}
