package io.androllm.core.jarvislink

import com.google.common.truth.Truth.assertThat
import io.androllm.core.jarvislink.processing.JarvisTaskRunner
import io.androllm.core.jarvislink.processing.RemoteTaskProcessor
import io.androllm.core.jarvislink.processing.RemoteTaskTracker
import io.androllm.core.jarvislink.processing.TaskDeduplicator
import io.androllm.core.jarvislink.processing.TaskOutcome
import io.androllm.core.jarvislink.protocol.JarvisProtocol
import io.androllm.core.jarvislink.protocol.ResultMessage
import io.androllm.core.jarvislink.protocol.ResultStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTaskProcessorTest {

    private val now = 1_800_000_000_000L
    private val published = mutableListOf<ResultMessage>()
    private val tracker = RemoteTaskTracker()

    private fun task(id: String, text: String = "hello", extra: String = "") =
        """{"id":"$id","text":"$text"$extra}""".toByteArray()

    private fun TestScope.processor(
        runner: JarvisTaskRunner,
        dedupe: TaskDeduplicator = TaskDeduplicator(),
        capacity: Int = 4
    ) = RemoteTaskProcessor(
        runner = runner,
        publisher = { published += JarvisProtocol.decodeResult(it) },
        deduplicator = dedupe,
        tracker = tracker,
        clock = { now },
        queueCapacity = capacity
    ).also { it.start(backgroundScope) }

    private fun statuses(id: String) = published.filter { it.id == id }.map { it.status }

    @Test
    fun `valid task is acked then answered with runner text`() = runTest {
        val seen = mutableListOf<String>()
        val p = processor({ t -> seen += t.text; TaskOutcome(ResultStatus.OK, "answer to ${t.text}") })
        p.onPayload(task("t1", "погода"))
        runCurrent()
        assertThat(seen).containsExactly("погода")
        assertThat(statuses("t1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(published.last().text).isEqualTo("answer to погода")
    }

    @Test
    fun `redelivered task id is answered duplicate and never re-run`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "sent") })
        p.onPayload(task("sms-1", "send sms"))
        runCurrent()
        p.onPayload(task("sms-1", "send sms"))
        runCurrent()
        assertThat(runs).isEqualTo(1)
        assertThat(statuses("sms-1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK, ResultStatus.DUPLICATE).inOrder()
    }

    @Test
    fun `invalid task with id gets invalid, without id is dropped`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "") })
        p.onPayload("""{"id":"bad","text":""}""".toByteArray())
        p.onPayload("garbage".toByteArray())
        runCurrent()
        assertThat(runs).isEqualTo(0)
        assertThat(published.map { it.id to it.status }).containsExactly("bad" to ResultStatus.INVALID)
    }

    @Test
    fun `expired task is reported once and not executed`() = runTest {
        var runs = 0
        val p = processor({ runs++; TaskOutcome(ResultStatus.OK, "") })
        val stale = task("old", extra = ""","ts":${now - 3_600_000},"ttl_s":60""")
        p.onPayload(stale)
        p.onPayload(stale)
        runCurrent()
        assertThat(runs).isEqualTo(0)
        assertThat(statuses("old")).containsExactly(ResultStatus.EXPIRED)
    }

    @Test
    fun `runner exception becomes error result and worker keeps going`() = runTest {
        val p = processor({ t -> if (t.id == "boom") error("engine crashed") else TaskOutcome(ResultStatus.OK, "fine") })
        p.onPayload(task("boom"))
        p.onPayload(task("next"))
        runCurrent()
        assertThat(statuses("boom")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.ERROR).inOrder()
        assertThat(published.first { it.id == "boom" && it.status == ResultStatus.ERROR }.text).contains("engine crashed")
        assertThat(statuses("next")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(tracker.active.value).isNull()
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
        runCurrent()            // worker picks "a" and blocks
        assertThat(tracker.active.value?.id).isEqualTo("a")
        p.onPayload(task("b"))        // fills the 1-slot queue
        p.onPayload(task("c"))        // queue full -> busy
        assertThat(statuses("c")).containsExactly(ResultStatus.BUSY)
        assertThat(dedupe.contains("c")).isFalse() // may be resent later
        gate.complete(Unit)
        runCurrent()
        assertThat(maxConcurrent).isEqualTo(1)
        assertThat(statuses("a")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
        assertThat(statuses("b")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.OK).inOrder()
    }

    @Test
    fun `runner denied outcome is forwarded`() = runTest {
        val p = processor({ TaskOutcome(ResultStatus.DENIED, "confirmations set to never") })
        p.onPayload(task("d1"))
        runCurrent()
        assertThat(statuses("d1")).containsExactly(ResultStatus.ACCEPTED, ResultStatus.DENIED).inOrder()
    }
}
