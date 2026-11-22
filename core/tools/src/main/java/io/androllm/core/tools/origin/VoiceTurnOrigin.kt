package io.androllm.core.tools.origin

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context marker for a turn the owner SPOKE to the voice assistant.
 *
 * The voice service launches the generation job with it; every coroutine below
 * (channelFlow, async tool batches, the executor, SideEffectGate) inherits it,
 * so [io.androllm.core.tools.confirmation.ToolConfirmationManager] can stamp
 * the card with [turnId]. The voice loop then knows a card belongs to ITS
 * turn: it holds the first-token timeout and barge-in while the card waits,
 * and announces tap-only cards (they cannot be approved by voice).
 *
 * Purely informational — it never relaxes any confirmation rule.
 */
class VoiceTurnOrigin(val turnId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VoiceTurnOrigin>
}
