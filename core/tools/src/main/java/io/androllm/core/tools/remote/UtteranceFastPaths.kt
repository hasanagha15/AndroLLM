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

    fun interface Handler {
        /** Reply text when handled, null = not handled (the model answers). */
        suspend fun tryHandle(utterance: String): String?
    }

    private val handlers = CopyOnWriteArrayList<Handler>()

    fun register(handler: Handler) {
        if (handler !in handlers) handlers += handler
    }

    fun unregister(handler: Handler) {
        handlers -= handler
    }

    /** The first handler's reply, or null. Null for remote turns and on any handler failure. */
    suspend fun tryHandle(utterance: String): String? {
        if (currentRemoteOrigin() != null) return null
        val text = utterance.trim()
        if (text.isEmpty() || text.length > 300) return null
        for (h in handlers) {
            val reply = try {
                h.tryHandle(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w("UtteranceFastPaths: handler failed (%s)", t.javaClass.simpleName)
                null
            }
            if (reply != null) return reply
        }
        return null
    }
}
