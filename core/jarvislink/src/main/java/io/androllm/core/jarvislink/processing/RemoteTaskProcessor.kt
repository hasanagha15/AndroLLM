package io.androllm.core.jarvislink.processing

import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.JarvisTask
import io.androllm.core.jarvislink.protocol.ResultStatus
import io.androllm.core.jarvislink.protocol.TaskParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Publishes a result payload on `jarvis/phone/result` (QoS 1). Throws on failure. */
fun interface ResultPublisher {
    suspend fun publish(payload: ByteArray)
}

/**
 * Turns raw task payloads into agent runs and answers on the result topic.
 *
 * Per payload: authenticate + validate (HMAC, mandatory ts, ttl) → dedupe by
 * id → rate limit → bounded queue (`busy` when full) → `accepted` → ONE worker
 * runs tasks strictly one at a time → ttl re-checked at dequeue → final status.
 *
 * Robustness:
 * - the worker survives any failure of a task or of a publish (a publish
 *   timeout is NOT allowed to cancel the loop);
 * - a final result that could not be published is kept (bounded) and re-sent
 *   by [flushPending] after the next reconnect;
 * - a redelivered id is answered `accepted` while running, or with the stored
 *   final result once done — it is never executed twice.
 */
class RemoteTaskProcessor(
    private val runner: JarvisTaskRunner,
    private val publisher: ResultPublisher,
    private val deduplicator: TaskDeduplicator,
    private val tracker: RemoteTaskTracker,
    private val signingKey: () -> ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxTasksPerHour: Int = DEFAULT_MAX_TASKS_PER_HOUR
) {
    private val queue = Channel<JarvisTask>(capacity = queueCapacity)
    private var worker: Job? = null
    private val lock = Mutex()
    private val queuedIds = LinkedHashSet<String>()
    private val finals = object : LinkedHashMap<String, ByteArray>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > FINALS_KEPT
    }
    private val unsent = ArrayDeque<ByteArray>()
    private val acceptedAt = ArrayDeque<Long>()

    /** Starts the single worker in [scope]; idempotent. */
    fun start(scope: CoroutineScope) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (task in queue) {
                try {
                    execute(task)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // real cancellation → stop
                    Timber.w(e, "JarvisLink: task %s hit a cancellation (timeout) — worker continues", task.id)
                } catch (e: Throwable) {
                    Timber.e(e, "JarvisLink: unexpected failure for %s — worker continues", task.id)
                } finally {
                    lock.withLock { queuedIds.remove(task.id) }
                    tracker.end(task)
                }
            }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
    }

    /** Handles one raw MQTT payload from the task topic. Never throws. */
    suspend fun onPayload(payload: ByteArray) {
        val now = clock()
        when (val parsed = JarvisProtocol.parseTask(payload, now, signingKey())) {
            is TaskParse.Invalid -> {
                Timber.w("JarvisLink: rejected task (%s)", parsed.reason)
                // Only authentic payloads are answered; forgeries are dropped silently.
                parsed.id?.let { reply(it, ResultStatus.INVALID, parsed.reason) }
            }
            is TaskParse.Expired -> {
                if (deduplicator.markIfNew(parsed.id)) {
                    reply(parsed.id, ResultStatus.EXPIRED, "Task arrived after its ttl; not executed.", final = true)
                }
            }
            is TaskParse.Valid -> accept(parsed.task, now)
        }
    }

    private suspend fun accept(task: JarvisTask, now: Long) {
        if (!deduplicator.markIfNew(task.id)) {
            val stored = lock.withLock { finals[task.id] }
            val inFlight = lock.withLock { task.id in queuedIds }
            when {
                stored != null -> safePublish(stored)
                inFlight -> reply(task.id, ResultStatus.ACCEPTED, "")
                else -> reply(task.id, ResultStatus.DUPLICATE, "Already received; not executed again.")
            }
            return
        }
        val limited = lock.withLock {
            while (acceptedAt.isNotEmpty() && now - acceptedAt.first() > HOUR_MS) acceptedAt.removeFirst()
            acceptedAt.size >= maxTasksPerHour
        }
        if (limited) {
            // id stays "seen": a captured task cannot be replayed later; Jarvis retries with a NEW id.
            reply(task.id, ResultStatus.BUSY, "Rate limit: max $maxTasksPerHour tasks per hour; retry later with a new id.", final = true)
            return
        }
        val queued = lock.withLock {
            if (queue.trySend(task).isSuccess) {
                queuedIds += task.id
                acceptedAt.addLast(now)
                true
            } else {
                false
            }
        }
        if (queued) {
            reply(task.id, ResultStatus.ACCEPTED, "")
        } else {
            // id stays "seen" (no replay of a captured task); Jarvis retries with a new id.
            reply(task.id, ResultStatus.BUSY, "Phone is busy with other tasks; retry with a new id.", final = true)
        }
    }

    private suspend fun execute(task: JarvisTask) {
        if (clock() > task.expiresAtMs()) {
            reply(task.id, ResultStatus.EXPIRED, "Task expired while queued; not executed.", final = true)
            return
        }
        tracker.begin(task)
        val outcome = try {
            runner.run(task)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            TaskOutcome(ResultStatus.ERROR, "Task was interrupted on the phone.")
        } catch (e: Exception) {
            Timber.e(e, "JarvisLink: task %s failed", task.id)
            TaskOutcome(ResultStatus.ERROR, "Task failed on the phone.")
        } finally {
            tracker.end(task)
        }
        val status = if (outcome.status.isFinal) outcome.status else ResultStatus.OK
        reply(task.id, status, outcome.text, final = true)
    }

    /** Re-sends final results that failed to publish (call after reconnect). */
    suspend fun flushPending() {
        while (true) {
            val next = lock.withLock { unsent.removeFirstOrNull() } ?: return
            if (!safePublish(next)) {
                lock.withLock { unsent.addFirst(next) }
                return
            }
        }
    }

    fun pendingCount(): Int = unsent.size

    private suspend fun reply(id: String, status: ResultStatus, text: String, final: Boolean = false) {
        val key = signingKey() ?: return
        val payload = JarvisProtocol.encodeResult(id, status, text, clock(), key)
        if (final) lock.withLock { finals[id] = payload }
        if (!safePublish(payload) && final) {
            lock.withLock {
                unsent.addLast(payload)
                while (unsent.size > MAX_UNSENT) unsent.removeFirst()
            }
        }
    }

    /** Publishes; returns false on any failure. Never lets a publish timeout cancel the caller. */
    private suspend fun safePublish(payload: ByteArray): Boolean = try {
        publisher.publish(payload)
        true
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        Timber.w("JarvisLink: publish timed out — will retry after reconnect")
        false
    } catch (e: Exception) {
        Timber.w("JarvisLink: publish failed (%s) — will retry after reconnect", e.javaClass.simpleName)
        false
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 4
        const val DEFAULT_MAX_TASKS_PER_HOUR = 30
        const val FINALS_KEPT = 64
        const val MAX_UNSENT = 32
        private const val HOUR_MS = 3_600_000L
    }
}
