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
    private val confirmationManager: ToolConfirmationManager
) : PlaybookDriver {

    override suspend fun snapshot(): NodeSnapshot? = controller.nodeSnapshot()

    override val uiEvents: StateFlow<Long> get() = controller.uiEvents

    override suspend fun tap(ref: NodeRef, approval: RiskApproval?): UiActionResult = controller.clickNodeRef(ref, approval)

    override suspend fun type(ref: NodeRef?, text: String): UiActionResult =
        if (ref != null) controller.typeIntoNodeRef(ref, text) else controller.type(text, null)

    override suspend fun scroll(direction: String): UiActionResult = controller.scroll(direction)

    override suspend fun back(): UiActionResult = controller.navigate("back")

    override suspend fun openDeeplink(pkg: String, uri: String): String? {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return "bad uri"
        val intent = Intent(Intent.ACTION_VIEW, u).setPackage(pkg)
        val resolvable = runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        if (!resolvable) return "not handled by the app"
        return if (ToolIntents.launch(context, intent)) null else "launch blocked"
    }

    override suspend fun launch(pkg: String): String? {
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
