package io.androllm.core.tools.confirmation

import io.androllm.core.tools.api.ToolCall
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.api.runtimePermissions
import io.androllm.core.tools.origin.RemoteToolOrigin
import io.androllm.core.tools.origin.VoiceTurnOrigin
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tool action waiting for user approval. The chat UI renders a card from
 * [pending]; the voice assistant reads [speakableQuestion] aloud and listens
 * for a yes/no reply.
 */
data class PendingToolConfirmation(
    val id: String,
    val toolName: String,
    val toolDisplayName: String,
    val actionSummary: String,
    val speakableQuestion: String,
    /**
     * Android runtime permissions the tool needs before it can run. The chat
     * card requests them (system dialog) when the user approves while they
     * are still missing, so approving a message actually lets it through.
     */
    val requiredPermissions: List<String> = emptyList(),
    /**
     * Non-null when the action was requested by a REMOTE task (Jarvis link):
     * the label shown to the user. Remote confirmations are never answered by
     * the voice responder — only by an explicit tap.
     */
    val remoteOrigin: String? = null,
    /**
     * Non-null when the card was raised inside a SPOKEN turn (stamped from
     * [VoiceTurnOrigin]); the voice loop uses it to recognise its own cards.
     */
    val voiceTurnId: String? = null,
    /**
     * Unique per decision (set by [ToolConfirmationManager] when the card is
     * published). Card ids are call ids, and local-model call ids are
     * deterministic (`call_<hash>_<index>`, `_fallback_0`), so the SAME id
     * comes back on a repeated request — only this number tells two cards
     * apart. 0 = not published yet.
     */
    val decisionSeq: Long = 0L,
    /**
     * Branch M: only an explicit tap on the card may approve it (risky UI
     * taps such as Send / Pay / Delete) — a spoken "yes" is never enough.
     */
    val tapOnly: Boolean = false,
    /**
     * Branch U: the card exists only because the tool needs a runtime permission
     * that is not granted yet ([requiredPermissions]); Approve opens the system
     * permission dialog, then runs the tool.
     */
    val permissionPrompt: Boolean = false
) {
    /** Which surface raised the card (scopes [ToolConfirmationManager.cancelPending]). */
    val surface: ConfirmationSurface
        get() = when {
            remoteOrigin != null -> ConfirmationSurface.REMOTE
            voiceTurnId != null -> ConfirmationSurface.VOICE
            else -> ConfirmationSurface.TYPED
        }
}

/** Origin of a confirmation card. */
enum class ConfirmationSurface { TYPED, VOICE, REMOTE }

/** Final outcome of one card ([approved] false also covers timeout / cancel). */
data class ConfirmationOutcome(
    val id: String,
    val approved: Boolean,
    /** [PendingToolConfirmation.decisionSeq] of the closed card. */
    val seq: Long = 0L
)

/**
 * Central hub for high-risk action confirmations, shared by the chat UI and
 * the voice assistant.
 *
 * - Chat: [awaitDecision] always publishes the request to [pending] and
 *   suspends until the user taps Approve/Deny on the confirmation card
 *   ([confirm]/[deny]). The card is ALWAYS live.
 * - Voice: the voice service registers a [voiceResponder] that speaks the
 *   question and listens for yes/no. It runs CONCURRENTLY with the card — the
 *   first decision (spoken or tapped) wins. A responder returning null
 *   abstains (e.g. muted / no TTS), leaving the card in charge.
 *
 * The chat card can never be bypassed by the voice assistant running in the
 * background: approving in chat resumes the executor even while the assistant
 * is speaking the same question.
 */
@Singleton
class ToolConfirmationManager @Inject constructor() {

    /** The action currently awaiting approval, or null when none. */
    private val _pending = MutableStateFlow<PendingToolConfirmation?>(null)
    val pending: StateFlow<PendingToolConfirmation?> = _pending

    private val responses = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val cards = java.util.concurrent.ConcurrentHashMap<String, PendingToolConfirmation>()

    /** Outcome of the most recently closed card (voice loop announces it). */
    private val _lastOutcome = MutableStateFlow<ConfirmationOutcome?>(null)
    val lastOutcome: StateFlow<ConfirmationOutcome?> = _lastOutcome

    /**
     * The last [OUTCOME_LOG_SIZE] outcomes, oldest first. A poller (the voice
     * loop ticks about once a second) reads this instead of [lastOutcome] so
     * two cards closing within one tick never lose a resolution.
     */
    private val _recentOutcomes = MutableStateFlow<List<ConfirmationOutcome>>(emptyList())
    val recentOutcomes: StateFlow<List<ConfirmationOutcome>> = _recentOutcomes

    private val decisionCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Optional voice-mode responder. Set by [io.androllm.feature.voice.service.
     * VoiceAssistantService] when the assistant is running; cleared on stop.
     *
     * Returns a DECISION (true = approve, false = deny) or null to ABSTAIN
     * (the voice surface could not ask — muted, no TTS, no reply heard) so the
     * chat card remains the decision surface.
     */
    @Volatile
    private var voiceResponder: (suspend (PendingToolConfirmation) -> Boolean?)? = null

    fun setVoiceResponder(responder: (suspend (PendingToolConfirmation) -> Boolean?)?) {
        voiceResponder = responder
    }

    /**
     * Suspends until the user decides. Returns true when approved. Times out
     * (deny) after [CONFIRMATION_TIMEOUT_MS] so a turn can never hang forever
     * on an unanswered card.
     */
    suspend fun awaitDecision(confirmation: PendingToolConfirmation): Boolean {
        // Remote-origin work (RemoteToolOrigin in the coroutine context) is
        // labelled even when the caller built the card itself (ui_run steps).
        val remote = currentCoroutineContext()[RemoteToolOrigin]
        val labelled = if (confirmation.remoteOrigin == null && remote != null) {
            confirmation.copy(
                remoteOrigin = remote.label,
                toolDisplayName = "${remote.label} · ${confirmation.toolDisplayName}"
            )
        } else {
            confirmation
        }
        val voiceTurn = currentCoroutineContext()[VoiceTurnOrigin]
        val effective = if (labelled.voiceTurnId == null && voiceTurn != null && labelled.remoteOrigin == null) {
            labelled.copy(voiceTurnId = voiceTurn.turnId)
        } else {
            labelled
        }
        // One card at a time: concurrent requests (typed turn + remote task)
        // queue instead of replacing each other on the single pending slot.
        return decisionLock.withLock { decide(effective.copy(decisionSeq = decisionCounter.incrementAndGet())) }
    }

    private val decisionLock = Mutex()

    private suspend fun decide(confirmation: PendingToolConfirmation): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        responses[confirmation.id] = deferred
        cards[confirmation.id] = confirmation
        _pending.value = confirmation
        var outcome = false
        return try {
            // Remote confirmations need an explicit tap — never a spoken "yes".
            // Car / messaging / glasses actions are tap-only too: a spoken reply
            // can be misheard (whisper noise words), so it never approves them.
            val responder = if (isTapOnly(confirmation)) null else voiceResponder
            val decision = if (responder == null) {
                withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
            } else {
                coroutineScope {
                    // The spoken question is ONE input; the chat card stays
                    // live as the other. The first real decision wins and the
                    // mic/utterance is cancelled immediately afterwards.
                    val voiceJob = launch {
                        val spoken = try {
                            // The voice surface (mic chunks, whisper decode, TTS)
                            // must never run on the caller's dispatcher — when
                            // this confirmation came from the chat the caller is
                            // the Main dispatcher, and that work would jank the
                            // UI. The voice service itself runs on Default.
                            withContext(Dispatchers.Default) { responder(confirmation) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            // A broken voice surface abstains — the card decides.
                            null
                        }
                        if (spoken != null) deferred.complete(spoken)
                    }
                    val decision =
                        withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
                    voiceJob.cancel()
                    decision
                }
            }
            outcome = decision
            decision
        } finally {
            responses.remove(confirmation.id)
            cards.remove(confirmation.id)
            if (_pending.value?.id == confirmation.id) _pending.value = null
            val closed = ConfirmationOutcome(confirmation.id, outcome, confirmation.decisionSeq)
            _recentOutcomes.update { (it + closed).takeLast(OUTCOME_LOG_SIZE) }
            _lastOutcome.value = closed
        }
    }

    /** Approves the pending action with [id] (chat card "Approve"). */
    fun confirm(id: String) {
        responses[id]?.complete(true)
    }

    /** Denies the pending action with [id] (chat card "Deny"). */
    fun deny(id: String) {
        responses[id]?.complete(false)
    }

    /**
     * Denies the pending cards raised by [surface] only (typed-chat Stop /
     * conversation switch must not deny a remote task's card, and stopping the
     * voice service must not deny a typed-chat card). Default = typed chat.
     */
    fun cancelPending(surface: ConfirmationSurface = ConfirmationSurface.TYPED) {
        cancelWhere { it.surface == surface }
    }

    /**
     * Branch U (step 5): denies only the cards with these ids (and only while they
     * are still live) — a timed-out chat turn closes ITS cards, never a remote
     * task's card raised meanwhile. Returns how many cards were denied.
     */
    fun cancelIds(ids: Collection<String>): Int {
        var n = 0
        for (id in ids) {
            if (responses[id]?.complete(false) == true) n++
        }
        return n
    }

    /** Denies every pending card regardless of origin. */
    fun cancelAllPending() {
        cancelWhere { true }
    }

    private fun cancelWhere(predicate: (PendingToolConfirmation) -> Boolean) {
        for ((id, card) in cards.entries.toList()) {
            if (predicate(card)) responses[id]?.complete(false)
        }
    }

    companion object {
        /** How long a chat confirmation card stays live before auto-denying. */
        const val CONFIRMATION_TIMEOUT_MS = 300_000L

        /** Outcomes kept in [recentOutcomes]. */
        const val OUTCOME_LOG_SIZE = 16

        /**
         * Tools whose confirmation may only be given by tapping the card.
         *
         * Branch U (step 5): every tool that talks to the outside world or destroys
         * data is tap-only — a misheard spoken "yes" must never send an SMS, place a
         * call, send an email, post a reply or delete something. Registry audit:
         * send_sms, make_call, send_email (outward communication), whatsapp_reply,
         * glasses_show_text (messaging / display to others), tesla_command (vehicle),
         * note_delete (deletion), share_text (hands text to another app).
         */
        val TAP_ONLY_TOOLS: Set<String> = setOf(
            "tesla_command", "whatsapp_reply", "glasses_show_text",
            "send_sms", "make_call", "send_email", "note_delete", "share_text"
        )

        /**
         * Branch U: tools registered later (MCP servers, plugins) whose NAME says they
         * send, call, post, reply, forward, share, pay or delete are tap-only too.
         */
        private val OUTWARD_NAME = Regex(
            "(^|[_.:-])(send|call|dial|post|publish|reply|forward|share|tweet|email|sms|message|" +
                "delete|remove|erase|wipe|trash|transfer|pay|purchase|buy|order|checkout|book|" +
                "upload|unlock|invite)(s|es|ed|ing)?([_.:-]|$)"
        )

        /** Branch U it.2 (B1): `postMessage` / `sendEmail` / `deleteFile` → `post_message` … */
        private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

        /** Canonical form for the name rule: camelCase split, then lowercase. */
        fun normalizeToolName(toolName: String): String =
            toolName.trim().replace(CAMEL_BOUNDARY, "$1_$2").lowercase()

        /**
         * True when [toolName] is a listed tap-only tool or its name says it sends,
         * calls, posts, deletes, pays, uploads… Branch U it.2 (B1): camelCase MCP
         * names (`mcp_slack_postMessage`) are split before matching, and the
         * executor RAISES a tap-only card for such a tool even when its spec does
         * not ask for confirmation.
         */
        fun isTapOnlyToolName(toolName: String): Boolean {
            val name = normalizeToolName(toolName)
            return name in TAP_ONLY_TOOLS || OUTWARD_NAME.containsMatchIn(name)
        }

        fun isTapOnly(confirmation: PendingToolConfirmation): Boolean =
            confirmation.remoteOrigin != null || confirmation.tapOnly ||
                isTapOnlyToolName(confirmation.toolName)
    }
}

/** Builds a [PendingToolConfirmation] for a call the executor is about to run. */
fun buildConfirmation(call: ToolCall, spec: ToolSpec): PendingToolConfirmation {
    val displayName = spec.permission?.displayName ?: spec.name.replace('_', ' ')
    val args = call.arguments.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().take(40)}" }
    val summary = if (args.isBlank()) "Run $displayName" else "Run $displayName — $args"
    // Templates like "send the SMS to {phone}" get their args substituted so
    // the spoken question names the actual recipient/contact.
    var phrase = spec.confirmationPrompt.ifBlank { spec.name.replace('_', ' ') }
    for ((key, value) in call.arguments) {
        val placeholder = "{$key}"
        if (placeholder in phrase) {
            phrase = phrase.replace(placeholder, value.toString().trim('"').take(60))
        }
    }
    return PendingToolConfirmation(
        id = call.id,
        toolName = call.name,
        toolDisplayName = displayName,
        actionSummary = summary,
        speakableQuestion = "Do you want me to $phrase? Say yes to confirm, or no to cancel.",
        requiredPermissions = spec.permission?.runtimePermissions().orEmpty()
    )
}
