package io.androllm.core.jarvistools.confirm

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager
import io.androllm.core.tools.confirmation.buildConfirmation
import io.androllm.core.tools.settings.AutomationSettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * Defense in depth for side-effect tools (Tesla commands, WhatsApp replies,
 * writing to the glasses).
 *
 * Normal path: every such tool has `requiresConfirmation = true`, so the
 * app's [io.androllm.core.tools.executor.ToolExecutor] already showed the
 * confirmation card before `execute()` runs. But the owner can set
 * Settings → Automation → Confirmation mode = "Never", which makes the
 * executor skip the card. For these tools that is not acceptable, so in that
 * mode the tool asks through the very same [ToolConfirmationManager] itself.
 * Result: exactly one confirmation in every mode.
 */
interface SideEffectGate {
    /** True when the action may proceed (confirmed now or by the executor). */
    suspend fun ensureConfirmed(spec: ToolSpec, arguments: JsonObject): Boolean
}

/** Pure decision logic (unit-tested): [executorConfirms] mirrors AutomationSettings.shouldConfirm. */
class ConfirmPolicyGate(
    private val executorConfirms: suspend (requiresConfirmation: Boolean) -> Boolean,
    private val ask: suspend (PendingToolConfirmation) -> Boolean
) : SideEffectGate {
    override suspend fun ensureConfirmed(spec: ToolSpec, arguments: JsonObject): Boolean {
        check(spec.requiresConfirmation) { "side-effect tool ${spec.name} must declare requiresConfirmation" }
        if (executorConfirms(true)) return true
        val call = ToolCall(id = "jarvis-${UUID.randomUUID()}", name = spec.name, arguments = arguments)
        return ask(buildConfirmation(call, spec))
    }
}

@Singleton
class AppSideEffectGate @Inject constructor(
    settingsStore: AutomationSettingsStore,
    confirmationManager: ToolConfirmationManager
) : SideEffectGate by ConfirmPolicyGate(
    executorConfirms = { req -> settingsStore.current().shouldConfirm(req) },
    ask = { confirmationManager.awaitDecision(it) }
)
