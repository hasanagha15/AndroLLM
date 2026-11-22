package io.androllm.core.jarvistools.confirm

import io.androllm.core.jarvistools.net.OkHttpTransport
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
 * mode [ensureConfirmed] asks through the very same [ToolConfirmationManager].
 *
 * [askDetailed] always asks with a card the tool built itself (e.g. the
 * WhatsApp recipient and the full text), whatever the mode.
 */
interface SideEffectGate {
    /** True when the action may proceed (confirmed now or by the executor). */
    suspend fun ensureConfirmed(spec: ToolSpec, arguments: JsonObject): Boolean

    /** Always shows [confirmation] and returns the owner's decision. */
    suspend fun askDetailed(confirmation: PendingToolConfirmation): Boolean
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

    override suspend fun askDetailed(confirmation: PendingToolConfirmation): Boolean = ask(confirmation)
}

@Singleton
class AppSideEffectGate @Inject constructor(
    settingsStore: AutomationSettingsStore,
    confirmationManager: ToolConfirmationManager
) : SideEffectGate by ConfirmPolicyGate(
    executorConfirms = { req -> settingsStore.current().shouldConfirm(req) },
    ask = { confirmationManager.awaitDecision(it) }
)

/**
 * Execution budgets. The executor wraps `execute()` in its timeout, and an
 * in-tool confirmation (up to [ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS])
 * runs inside it — so side-effect tools declare a budget that fits the card
 * plus the network, and refuse to start a network call that could outlive it
 * (a late "yes" must not produce a command that runs after the tool reported
 * a timeout).
 */
object SideEffectBudget {
    private const val MARGIN_MS = 10_000L
    const val CONFIRM_MS = ToolConfirmationManager.CONFIRMATION_TIMEOUT_MS
    const val NET_MS = OkHttpTransport.NETWORK_BUDGET_MS

    /** One in-tool card + one network call (Tesla command, glasses). */
    const val ONE_CALL_MS = CONFIRM_MS + NET_MS + MARGIN_MS
    /** One card + a lookup before it + the send after it (WhatsApp reply). */
    const val TWO_CALLS_MS = CONFIRM_MS + 2 * NET_MS + MARGIN_MS

    fun hasNetworkBudget(startedAtMs: Long, timeoutMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        (nowMs - startedAtMs) + NET_MS + MARGIN_MS / 2 <= timeoutMs
}
