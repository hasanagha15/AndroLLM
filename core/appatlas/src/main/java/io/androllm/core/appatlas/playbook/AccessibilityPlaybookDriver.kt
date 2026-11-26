package io.androllm.core.appatlas.playbook

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.accessibility.controller.NodeRef
import io.androllm.core.accessibility.controller.NodeSnapshot
import io.androllm.core.accessibility.controller.RiskApproval
import io.androllm.core.accessibility.controller.UiActionResult
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.tool.impl.ToolIntents
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Production [PlaybookDriver]: the accessibility controller + the shared confirmation manager. */
@Singleton
class AccessibilityPlaybookDriver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AccessibilityController,
    private val confirmationManager: ToolConfirmationManager,
    private val ownRun: OwnRunTracker
) : PlaybookDriver {

    /** Monotonic clock (elapsedRealtime); replaceable in unit tests. */
    internal var elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** Branch W3: our own actions of a Jarvis run are marked so `owner_active` can ignore their events. */
    private suspend inline fun <T> own(block: () -> T): T {
        val remote = io.androllm.core.tools.origin.currentRemoteOrigin() != null
        if (remote) ownRun.actionStart(elapsed())
        try {
            return block()
        } finally {
            if (remote) ownRun.actionEnd(elapsed())
        }
    }

    override suspend fun snapshot(): NodeSnapshot? = controller.nodeSnapshot()

    override val uiEvents: StateFlow<Long> get() = controller.uiEvents

    override suspend fun tap(ref: NodeRef, approval: RiskApproval?): UiActionResult = own { controller.clickNodeRef(ref, approval) }

    override suspend fun type(ref: NodeRef?, text: String): UiActionResult =
        own { if (ref != null) controller.typeIntoNodeRef(ref, text) else controller.type(text, null) }

    override suspend fun scroll(direction: String): UiActionResult = own { controller.scroll(direction) }

    override suspend fun back(): UiActionResult = own { controller.navigate("back") }

    override suspend fun openDeeplink(pkg: String, uri: String): String? = own { openDeeplinkNow(pkg, uri) }

    private fun openDeeplinkNow(pkg: String, uri: String): String? {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return "bad uri"
        val intent = Intent(Intent.ACTION_VIEW, u).setPackage(pkg)
        val resolvable = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        if (!resolvable) return "not handled by the app"
        return if (ToolIntents.launch(context, intent)) null else "launch blocked"
    }

    override suspend fun launch(pkg: String): String? = own { launchNow(pkg) }

    private fun launchNow(pkg: String): String? {
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return "no launcher activity"
        return if (ToolIntents.launch(context, intent)) null else "launch blocked"
    }

    override suspend fun confirmTap(summary: String): Boolean {
        val card = PendingToolConfirmation(
            id = "pb_${System.currentTimeMillis()}_${summary.hashCode().toUInt().toString(16)}",
            toolName = PlaybookRunTool.NAME,
            toolDisplayName = "Playbook",
            actionSummary = "proceed with: $summary",
            speakableQuestion = "Please confirm on the screen: $summary.",
            tapOnly = true
        )
        return withTimeoutOrNull(ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS) { confirmationManager.awaitDecision(card) } ?: false
    }
}
