package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.TestTasks.signed
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.ResultPublisher
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultMessage
import io.androllm.core.jarvislink.protocol.ResultStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTaskProcessorTest {

    private var now = 1_800_000_000_000L
    private val published = mutableListOf<ResultMessage>()
    private val tracker = RemoteTaskTracker()
    private val recorder = ResultPublisher { published += JarvisProtocol.decodeResult(it) }

    private fun task(id: String, text: String = "hello", ts: Long = now, ttl: Long = 600) = signed(id, text, ts = ts, ttl = ttl)

    private fun TestScope.processor(
        runner: JarvisTaskRunner,
        dedupe: TaskDeduplicator = TaskDeduplicator(),
        capacity: Int = 4,
        publisher: ResultPublisher = recorder,
        perHour: Int = 30
    ) = RemoteTaskProcessor(
        runner = runner,
        publisher = publisher,
        deduplicator = dedupe,
        tracker = tracker,
        signingKey = { TestTasks.KEY },
        clock = { now },
        queueCapacity = capacity,
        maxTasksPerHour = perHour
    ).also { it.start(backgroundScope) }

    private fun statuses(id: String) = published.filter { it.id == id }.map { it.status }

    @Test
    fun `valid task is acked then answered with signed runner text`() = runTest {
        val p = processor({ t -> TaskOutcome(ResultStatus.OK, "answer to ${t.text}") })
        p.onPayload(task("t1", "погода"))
        runCurrent()
        assertThat(statuses("t1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(published.last().text).isEqualTo("answer to погода")
        assertThat(JarvisProtocol.verifyResult(published.last(), TestTasks.KEY)).isTrue()
    }

    @Test
    fun `forged task is neither run nor answered`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "") })
        p.onPayload(signed("evil", "send sms", ts = now, key = TestTasks.OTHER_KEY))
        p.onPayload(signed("evil2", ts = now, omitSig = true))
        runCurrent()
        assertThat(runs).isEqualTo(0)
        assertThat(published).isEmpty()
    }

    @Test
    fun `redelivery after completion replays the stored final result and never re-runs`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "sent") })
        p.onPayload(task("sms-1"))
        runCurrent()
        p.onPayload(task("sms-1"))
        runCurrent()
        assertThat(runs).isEqualTo(1)
        assertThat(statuses("sms-1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK, ResultStatus.OK).inOrder()
    }

    @Test
    fun `redelivery while running is answered accepted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val p = processor({ runs++; gate.await(); TaskOutcome(ResultStatus.OK, "done") })
        p.onPayload(task("r1"))
        runCurrent()
        p.onPayload(task("r1"))
        runCurrent()
        assertThat(statuses("r1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.ACCEPTED).inOrder()
        gate.complete(Unit)
        runCurrent()
        assertThat(runs).isEqualTo(1)
        assertThat(statuses("r1").last()).isEqualTo(ResultStatus.OK)
    }

    @Test
    fun `authentic invalid task is answered invalid`() = runTest {
        val p = processor({ TaskOutcome(ResultStatus.OK, "") })
        p.onPayload(task("bad", text = "   "))
        runCurrent()
        assertThat(published.map { it.id to it.status }).containsExactly("bad" to ResultStatus.INVALID)
    }

    @Test
    fun `expired on arrival is reported once`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "") })
        val stale = task("old", ts = now - 3_600_000, ttl = 60)
        p.onPayload(stale)
        p.onPayload(stale)
        runCurrent()
        assertThat(runs).isEqualTo(0)
        assertThat(statuses("old")).containsExactly(ResultStatus.EXPIRED)
    }

    @Test
    fun `ttl is re-checked when the task is dequeued`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val p = processor({ t -> ran += t.id; if (t.id == "first") gate.await(); TaskOutcome(ResultStatus.OK, "") })
        p.onPayload(task("first", ttl = 3_600))
        runCurrent()
        p.onPayload(task("short", ttl = 60))
        runCurrent()
        now += 60_000 + JarvisProtocol.CLOCK_SKEW_MS + 1 // "short" expires while "first" waits
        gate.complete(Unit)
        runCurrent()
        assertThat(ran).containsExactly("first")
        assertThat(statuses("short")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.EXPIRED).inOrder()
    }

    @Test
    fun `runner exception becomes a generic error and the worker keeps going`() = runTest {
        val p = processor({ t -> if (t.id == "boom") error("secret /data/path leaked?") else TaskOutcome(ResultStatus.OK, "fine") })
        p.onPayload(task("boom"))
        p.onPayload(task("next"))
        runCurrent()
        val err = published.first { it.id == "boom" && it.status == ResultStatus.ERROR }
        assertThat(err.text).doesNotContain("secret") // no raw exception text leaves the phone
        assertThat(statuses("next")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(tracker.active.value).isNull()
    }

    @Test
    fun `publish timeout does not kill the worker and the result is re-sent after reconnect`() = runTest {
        var online = true
        val sent = mutableListOf<ResultMessage>()
        val flaky = ResultPublisher { payload ->
            if (!online) {
                // what a hung broker connection looks like to the caller
                withTimeout(30_000) { delay(60_000) }
            }
            sent += JarvisProtocol.decodeResult(payload)
        }
        val p = processor({ t -> TaskOutcome(ResultStatus.OK, "r-${t.id}") }, publisher = flaky)
        p.onPayload(task("a"))          // accepted goes out
        online = false
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(31_000)           // final for "a" times out
        runCurrent()
        assertThat(p.pendingCount()).isEqualTo(1)
        online = true
        p.onPayload(task("b"))          // worker must still be alive
        runCurrent()
        assertThat(sent.filter { it.id == "b" }.map { it.status }).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        p.flushPending()                // after reconnect
        assertThat(sent.filter { it.id == "a" }.map { it.status }).contains(ResultStatus.OK)
        assertThat(p.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `tasks run strictly one at a time and full queue answers busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var concurrent = 0
        var maxConcurrent = 0
        val dedupe = TaskDeduplicator()
        val p = processor({ t ->
            concurrent++; maxConcurrent = maxOf(maxConcurrent, concurrent)
            if (t.id == "a") gate.await()
            concurrent--
            TaskOutcome(ResultStatus.OK, t.id)
        }, dedupe = dedupe, capacity = 1)
        p.onPayload(task("a"))
        runCurrent()
        assertThat(tracker.active.value?.id).isEqualTo("a")
        p.onPayload(task("b"))
        p.onPayload(task("c"))
        assertThat(statuses("c")).containsExactly(ResultStatus.BUSY)
        assertThat(dedupe.contains("c")).isFalse()
        gate.complete(Unit)
        runCurrent()
        assertThat(maxConcurrent).isEqualTo(1)
        assertThat(statuses("b")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
    }

    @Test
    fun `rate limit answers busy`() = runTest {
        val p = processor({ TaskOutcome(ResultStatus.OK, "") }, perHour = 2)
        p.onPayload(task("1")); p.onPayload(task("2")); p.onPayload(task("3"))
        runCurrent()
        assertThat(statuses("3")).containsExactly(ResultStatus.BUSY)
        now += 3_600_001
        p.onPayload(task("4", ts = now))
        runCurrent()
        assertThat(statuses("4")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
    }

    @Test
    fun `runner denied outcome is forwarded`() = runTest {
        val p = processor({ TaskOutcome(ResultStatus.DENIED, "confirmations set to never") })
        p.onPayload(task("d1"))
        runCurrent()
        assertThat(statuses("d1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.DENIED).inOrder()
    }
}
