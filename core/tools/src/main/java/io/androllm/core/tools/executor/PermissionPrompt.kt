package io.androllm.core.tools.executor

import io.androllm.core.tools.confirmation.PendingToolConfirmation

/**
 * Branch U: card raised only because a tool's runtime permission is missing
 * (e.g. CAMERA for set_flashlight). Approve opens the system permission dialog
 * (ChatScreen requests [PendingToolConfirmation.requiredPermissions]) and then
 * runs the tool. Tap-only: a spoken "yes" cannot grant an Android permission.
 */
internal object PermissionPrompt {

    fun card(base: PendingToolConfirmation, missing: List<String>): PendingToolConfirmation {
        val names = missing.joinToString(", ") { it.substringAfterLast('.').replace('_', ' ').lowercase() }
        return base.copy(
            actionSummary = "Needs the $names permission" + "\n" + base.actionSummary,
            speakableQuestion = "This needs the $names permission. Please approve it on the screen.",
            requiredPermissions = missing,
            permissionPrompt = true,
            tapOnly = true
        )
    }
}
