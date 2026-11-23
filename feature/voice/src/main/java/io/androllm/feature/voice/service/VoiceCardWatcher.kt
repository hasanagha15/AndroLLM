package io.androllm.feature.voice.service

import io.androllm.core.tools.confirmation.ConfirmationOutcome
import io.androllm.core.tools.confirmation.PendingToolConfirmation
import io.androllm.core.tools.confirmation.ToolConfirmationManager

/**
 * Tracks the confirmation cards of ONE spoken turn (pure, unit-tested).
 *
 * The voice loop feeds it the manager's current card and last outcome on
 * every tick. It reports:
 * - [Event.TapOnlyCard] once per tap-only card of this turn (car, messaging,
 *   glasses or remote cards can't be approved by voice) → the loop speaks a
 *   hint and posts a notification;
 * - [Event.Resolved] when such a card closes → the loop speaks "cancelled"
 *   on a deny/timeout (on approve the model speaks the tool result).
 *
 * [holdsTurn] is true while one of this turn's cards waits (any card, also
 * one the voice responder is asking about) and for [postApprovalGraceMs]
 * after an approval until the next activity (answer token / tool status):
 * the approved tool may run long (Tesla bridge up to ~75 s) with no status.
 * While it holds, the loop neither counts the first-token timeout nor
 * listens for barge-in, so a waiting card is never torn down by silence or
 * room noise. The card itself still auto-denies after the manager's
 * 300 s timeout.
 */
class VoiceCardWatcher(
    private val turnId: String,
    private val postApprovalGraceMs: Long = POST_APPROVAL_GRACE_MS
) {

    sealed interface Event {
        data class TapOnlyCard(val card: PendingToolConfirmation) : Event
        data class Resolved(val card: PendingToolConfirmation, val approved: Boolean) : Event
    }

    /**
     * Tap-only cards of this turn announced and still awaiting an outcome,
     * keyed by [PendingToolConfirmation.decisionSeq] — NOT by id: call ids
     * repeat (deterministic local ids), so an older outcome with the same id
     * must never resolve a newer card.
     */
    private val announced = LinkedHashMap<Long, PendingToolConfirmation>()
    private val closed = HashSet<Long>()
    private var ownCardPending = false
    private var approvedAtMs: Long? = null

    /** True when [card] was raised by this spoken turn. */
    fun isOwn(card: PendingToolConfirmation?): Boolean = card != null && card.voiceTurnId == turnId

    /**
     * [outcomes] is the manager's recent-outcome log
     * ([ToolConfirmationManager.recentOutcomes]); entries are matched to
     * announced cards by their decision sequence number only.
     */
    @Synchronized
    fun update(
        pending: PendingToolConfirmation?,
        outcomes: List<ConfirmationOutcome>,
        nowMs: Long
    ): List<Event> {
        val events = ArrayList<Event>(2)
        for (outcome in outcomes) {
            if (outcome.seq <= 0L) continue
            announced.remove(outcome.seq)?.let { card ->
                closed += outcome.seq
                events += Event.Resolved(card, outcome.approved)
                approvedAtMs = if (outcome.approved) nowMs else null
            }
        }
        val own = pending?.takeIf { isOwn(it) }
        if (own != null && ToolConfirmationManager.isTapOnly(own) &&
            own.decisionSeq !in announced && own.decisionSeq !in closed
        ) {
            announced[own.decisionSeq] = own
            events += Event.TapOnlyCard(own)
        }
        ownCardPending = own != null
        return events
    }

    /** An answer token or tool status arrived: the post-approval grace ends. */
    @Synchronized
    fun onActivity() {
        approvedAtMs = null
    }

    @Synchronized
    fun holdsTurn(nowMs: Long): Boolean =
        ownCardPending || announced.isNotEmpty() ||
            (approvedAtMs?.let { nowMs - it < postApprovalGraceMs } ?: false)

    companion object {
        /** Longest a single approved side-effect call may run silently (Tesla bridge ≈ 75 s). */
        const val POST_APPROVAL_GRACE_MS = 90_000L
    }
}
