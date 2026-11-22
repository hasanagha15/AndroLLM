package io.androllm.core.jarvislink.processing

import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/** Publishes a result payload on `jarvis/phone/result` (QoS 1). */
fun interface ResultPublisher {
    suspend fun publish(payload: ByteArray)
}

/**
 * Turns raw task payloads into agent runs and answers on the result topic.
 *
 * Flow per payload: parse/validate → TTL check → dedupe by id → enqueue
 * (bounded; full = `busy`) → `accepted` ack → ONE worker runs tasks strictly
 * one at a time through [JarvisTaskRunner] → final `ok`/`error`/`denied`.
 *
 * Tasks are serialized because the confirmation surface (chat card /
 * notification) shows one pending action at a time and a phone agent driving
 * the UI cannot do two things at once.
 */
class RemoteTaskProcessor(
    private val runner: JarvisTaskRunner,
    private val publisher: ResultPublisher,
    private val deduplicator: TaskDeduplicator,
    private val tracker: RemoteTaskTracker,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) {
    private val queue = Channel<JarvisTask>(capacity = queueCapacity)
    private var worker: Job? = null

    /** Starts the single worker in [scope]; idempotent. */
    fun start(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (task in queue) execute(task)
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
    }

    /** Handles one raw MQTT payload from the task topic. Never throws. */
    suspend fun onPayload(payload: ByteArray) {
        when (val parsed = JarvisProtocol.parseTask(payload, clock())) {
            is TaskParse.Invalid -> {
                Timber.w("JarvisLink: invalid task (%s)", parsed.reason)
                parsed.id?.let { reply(it, ResultStatus.INVALID, parsed.reason) }
            }
            is TaskParse.Expired -> {
                Timber.i("JarvisLink: task %s expired (age %d ms) — not executed", parsed.id, parsed.ageMs)
                if (deduplicator.markIfNew(parsed.id)) {
                    reply(parsed.id, ResultStatus.EXPIRED, "Task arrived after its ttl; not executed.")
                }
            }
            is TaskParse.Valid -> {
                val task = parsed.task
                if (!deduplicator.markIfNew(task.id)) {
                    Timber.i("JarvisLink: duplicate task %s ignored", task.id)
                    reply(task.id, ResultStatus.DUPLICATE, "")
                    return
                }
                if (queue.trySend(task).isSuccess) {
                    reply(task.id, ResultStatus.ACCEPTED, "")
                } else {
                    // Refused before running — forget it so Jarvis can resend later.
                    deduplicator.forget(task.id)
                    reply(task.id, ResultStatus.BUSY, "Phone is busy with other tasks; retry later.")
                }
            }
        }
    }

    private suspend fun execute(task: JarvisTask) {
        tracker.begin(task)
        val outcome = try {
            runner.run(task)
        } catch (e: CancellationException) {
            tracker.end(task)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "JarvisLink: task %s failed", task.id)
            TaskOutcome(ResultStatus.ERROR, "Task failed: ${e.message ?: e::class.java.simpleName}")
        } finally {
            tracker.end(task)
        }
        val status = if (outcome.status.isFinal) outcome.status else ResultStatus.OK
        reply(task.id, status, outcome.text)
    }

    private suspend fun reply(id: String, status: ResultStatus, text: String) {
        try {
            publisher.publish(JarvisProtocol.encodeResult(id, status, text, clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "JarvisLink: could not publish %s for %s", status, id)
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 4
    }
}
