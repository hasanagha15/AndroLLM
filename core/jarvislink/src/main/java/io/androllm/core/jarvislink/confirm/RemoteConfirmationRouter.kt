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
    fun show(confirmation: PendingToolConfirmation, task: JarvisTask)
    fun cancel()
}

/**
 * Routes confirmations raised while a REMOTE task runs to a heads-up
 * notification, so the owner sees them even when the chat screen is closed.
 *
 * It adds a surface, never a decision path of its own:
 * - Confirmations are still raised by `ToolExecutor` → [ToolConfirmationManager.awaitDecision]
 *   exactly as for typed requests; the chat card stays live in parallel and the
 *   first answer wins.
 * - [approve]/[deny] are only reachable from the user's tap on the notification
 *   (non-exported receiver, immutable PendingIntent, device unlock required),
 *   and they only act on the id that is pending right now.
 * - No answer → the manager's own timeout denies. Nothing here ever approves
 *   automatically, and Jarvis has no MQTT message that could approve.
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
                    if (pending != null && task != null) notifier.show(pending, task) else notifier.cancel()
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        notifier.cancel()
    }

    /** User tapped Approve on the notification for confirmation [id]. */
    fun approve(id: String): Boolean {
        if (confirmationManager.pending.value?.id != id) return false
        confirmationManager.confirm(id)
        return true
    }

    /** User tapped Deny on the notification for confirmation [id]. */
    fun deny(id: String): Boolean {
        if (confirmationManager.pending.value?.id != id) return false
        confirmationManager.deny(id)
        return true
    }
}
