package io.androllm.core.tools.remote

import io.androllm.core.tools.origin.currentRemoteOrigin
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * Branch N: zero-LLM fast path for the owner's own typed / spoken commands.
 * A handler (core:appatlas playbooks) returns the reply when it fully handled
 * the utterance, or null to let the model answer as usual. Never consulted
 * for remote (Jarvis) turns. A static registry like UiStepJournal, so the
 * chat / voice entry points need only one call and no new dependencies.
 */
object UtteranceFastPaths {

    /**
     * Branch Q: result of one fast-path attempt.
     * - [reply] non-null: fully handled, show / speak it, no model call;
     * - [reply] null + [modelNote]: a replay started and stopped part-way;
     *   the model answers, and [modelNote] (app-authored, ids / counts / op
     *   types only — never slot values or screen text) tells it what was
     *   already done on screen;
     * - both null: not handled, the model answers as before.
     */
    data class Outcome(val reply: String? = null, val modelNote: String? = null) {
        companion object {
            val NONE = Outcome()
        }
    }

    fun interface Handler {
        /** Reply text when handled, null = not handled (the model answers). */
        suspend fun tryHandle(utterance: String): String?

        /** Branch Q: full outcome incl. a note for the model after a partial replay. */
        suspend fun handle(utterance: String): Outcome = Outcome(reply = tryHandle(utterance))
    }

    private val handlers = CopyOnWriteArrayList<Handler>()

    fun register(handler: Handler) {
        if (handler !in handlers) handlers += handler
    }

    fun unregister(handler: Handler) {
        handlers -= handler
    }

    /** The first handler's reply, or null. Null for remote turns and on any handler failure. */
    suspend fun tryHandle(utterance: String): String? = handle(utterance).reply

    /**
     * The first handler's reply; otherwise the first note left by a handler
     * whose replay stopped part-way. [Outcome.NONE] for remote turns.
     */
    suspend fun handle(utterance: String): Outcome {
        if (currentRemoteOrigin() != null) return Outcome.NONE
        val text = utterance.trim()
        if (text.isEmpty() || text.length > 300) return Outcome.NONE
        var note: String? = null
        for (h in handlers) {
            val outcome = try {
                h.handle(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w("UtteranceFastPaths: handler failed (%s)", t.javaClass.simpleName)
                Outcome.NONE
            }
            if (outcome.reply != null) return outcome
            if (note == null) note = outcome.modelNote
        }
        return Outcome(modelNote = note)
    }
}
